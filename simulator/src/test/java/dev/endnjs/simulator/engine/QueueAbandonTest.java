package dev.endnjs.simulator.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.endnjs.simulator.http.JsonCodec;
import org.junit.jupiter.api.Test;
import static dev.endnjs.simulator.engine.HttpTransport.Target.*;
import static org.assertj.core.api.Assertions.*;

/** 대기 이탈: 대기 중 사용자가 순번 조회 응답마다 1 − 0.5^(Δt ÷ 반감기)로 판단. 줄이 멈추면 반감기 절반. */
class QueueAbandonTest {
    private static final long SEC=1_000_000_000L;
    private static final int USERS=4000;
    /** 표본 4,000명: 비율의 표준편차 ≈ 0.008. 허용 오차 ±0.03 (약 3.8σ). */
    private static final double TOLERANCE=.03;
    private final JsonCodec json=new JsonCodec();
    private RunConfig config(String extra) { return json.config("{\"timeScale\":1"+extra+"}"); }

    /** 2초마다 순번 조회. position(t초) → 그 시각 순번. 떠나면 떠난 시각(초), 끝까지 남으면 -1. */
    private static double waitUntilLeave(QueueAbandon abandon,double seconds,java.util.function.DoubleToLongFunction position) {
        abandon.start(0,position.applyAsLong(0));
        for(double t=2;t<=seconds+1e-9;t+=2) if(abandon.leave(Math.round(t*SEC),position.applyAsLong(t))) return t;
        return -1;
    }
    private static double share(List<Double> leftAt,java.util.function.DoublePredicate counted) {
        return leftAt.stream().filter(t -> counted.test(t)).count()/(double)leftAt.size();
    }

    @Test void casualHalfLifeWhileTheQueueMoves() {
        var config=config("");var leftAt=new ArrayList<Double>();
        for(int i=0;i<USERS;i++) leftAt.add(waitUntilLeave(new QueueAbandon(ChurnPolicy.Type.casual,config,QueueAbandon.stream(config,i)),180,
                t -> Math.round(1800-t*5))); // 60초에 300 줄어듦 (≥ 5%): 멈춤 아님
        assertThat(share(leftAt,t -> t>0)).isCloseTo(.5,within(TOLERANCE));
    }
    @Test void stalledQueueHalvesTheHalfLifeAfterTheFirstWindow() {
        var config=config("");var leftAt=new ArrayList<Double>();
        for(int i=0;i<USERS;i++) leftAt.add(waitUntilLeave(new QueueAbandon(ChurnPolicy.Type.casual,config,QueueAbandon.stream(config,i)),150,t -> 900));
        // 처음 60초는 멈춤 판단 없음 (반감기 180): 60초까지 이탈 1 − 0.5^(1/3) ≈ 0.206
        assertThat(share(leftAt,t -> t>0 && t<=60)).isCloseTo(1-Math.pow(.5,60/180.0),within(TOLERANCE));
        // 60초 뒤 줄 멈춤: 반감기 90. 60초에 남은 사람 중 다음 90초 안에 떠나는 비율 ≈ 50%
        double stayed=share(leftAt,t -> t<0 || t>60),leftLater=share(leftAt,t -> t>60);
        assertThat(leftLater/stayed).isCloseTo(.5,within(TOLERANCE));
    }
    @Test void persistentUsesItsOwnHalfLife() {
        var config=config("");var leftAt=new ArrayList<Double>();
        for(int i=0;i<USERS;i++) leftAt.add(waitUntilLeave(new QueueAbandon(ChurnPolicy.Type.persistent,config,QueueAbandon.stream(config,i)),600,
                t -> Math.round(6000-t*5)));
        assertThat(share(leftAt,t -> t>0)).isCloseTo(.5,within(TOLERANCE));
    }
    @Test void hardcoreNeverLeavesTheQueue() {
        var config=config("");
        for(int i=0;i<200;i++) assertThat(waitUntilLeave(new QueueAbandon(ChurnPolicy.Type.hardcore,config,QueueAbandon.stream(config,i)),36000,t -> 900)).isNegative();
    }
    @Test void disabledNeverLeavesAndDrawsNothing() {
        var config=config(",\"queueAbandonEnabled\":false");
        for(int i=0;i<200;i++) {
            var random=QueueAbandon.stream(config,i);long before=random.split().nextLong();
            var replay=QueueAbandon.stream(config,i);
            assertThat(waitUntilLeave(new QueueAbandon(ChurnPolicy.Type.casual,config,replay),3600,t -> 900)).isNegative();
            assertThat(replay.split().nextLong()).isEqualTo(before); // 난수를 하나도 쓰지 않음
        }
    }
    @Test void queueDecisionsUseASeparateStreamSoProfilesStayTheSame() {
        var on=config("");var off=config(",\"queueAbandonEnabled\":false");
        for(int i=0;i<50;i++) {
            var a=VirtualUser.profile(on,i);var b=VirtualUser.profile(off,i);
            assertThat(a.arrivalMillis()).isEqualTo(b.arrivalMillis());assertThat(a.churn()).isEqualTo(b.churn());assertThat(a.persona()).isEqualTo(b.persona());
            // 사용자의 대기 이탈 판단기(VirtualUser가 쓰는 forUser)는 별도 스트림: 같은 판단을 다시 만들 수 있고 본래 난수는 그대로
            double viaUser=waitUntilLeave(QueueAbandon.forUser(on,i,ChurnPolicy.Type.casual),600,t -> 900);
            double viaStream=waitUntilLeave(new QueueAbandon(ChurnPolicy.Type.casual,on,QueueAbandon.stream(on,i)),600,t -> 900);
            assertThat(viaUser).isEqualTo(viaStream);
            assertThat(a.random().nextLong()).isEqualTo(b.random().nextLong());
        }
        assertThat(QueueAbandon.stream(on,0).nextLong()).isNotEqualTo(VirtualUser.profile(on,0).random().nextLong());
    }
    @Test void defaultsTurnItOnButRecordsWithoutTheFieldStayOff() {
        assertThat(RunConfig.defaults().queueAbandonEnabled()).isTrue();
        assertThat(config("").queueAbandonEnabled()).isTrue(); // API·프리셋: 기본값과 합침
        var legacy=RunConfig.defaults();
        var stored=new RunConfig(legacy.rows(),legacy.cols(),legacy.grades(),legacy.users(),legacy.arrival(),legacy.personaMix(),legacy.personas(),
                legacy.abandonRate(),legacy.seed(),legacy.maxActive(),legacy.admitPerSec(),legacy.admissionTtlSec(),legacy.closeQueueOnSoldOut(),
                legacy.holdTtlSec(),legacy.confirmDeadlineSec(),legacy.authFailureRate(),legacy.declineRate(),legacy.confirmMinMs(),legacy.confirmMaxMs(),
                legacy.timeoutRate(),legacy.strategy(),legacy.dbBackstop(),legacy.timeLimitSec(),legacy.targets(),legacy.timeScale(),legacy.saleDurationSec(),
                legacy.churnMix(),legacy.casualLeaveSec(),legacy.persistentHalfLifeSec(),legacy.revisitProb(),legacy.maxSeatsPerUser(),legacy.ticketCountMix(),
                legacy.adjacentRequiredRate(),legacy.paymentMix(),legacy.depositDeadlineSec(),legacy.depositNoPayRate(),legacy.returnDelaySec(),
                legacy.reopenWindowSec(),legacy.cancelAfterPurchaseRate(),legacy.priceStepSec(),legacy.queueMode(),legacy.busyMaxExtraSec(),
                legacy.requestTimeoutMs(),legacy.label(),legacy.notes(),legacy.thresholds(),null,null,null,null,null,null,null,null,null,null);
        assertThat(stored.queueAbandonEnabled()).isFalse(); // 이 규칙 전에 저장된 실행
        assertThat(stored.seatsRateLimitEnabled()).isFalse();assertThat(stored.seatsCacheSec()).isZero(); // 새로고침 제한·캐시 전에 저장된 실행
        assertThat(stored.revisitRetryEnabled()).isFalse(); // 재방문 재시도 전에 저장된 실행
    }

    private record EngineRun(Summary summary,List<String> calls) {}
    /** casual 1명, 줄이 멈춘 대기열(순번 900 그대로). 판매 종료 뒤에는 대기열이 CLOSED(SALE_ENDED). */
    private EngineRun engineRun(boolean enabled) {
        var config=json.config("""
                {"users":1,"rows":1,"cols":4,"grades":[{"name":"VIP","rows":1,"price":100}],"arrival":[{"percent":100,"fromSec":0,"toSec":0}],
                 "churnMix":{"casual":100,"persistent":0,"hardcore":0},"timeScale":4,"saleDurationSec":3600,"timeLimitSec":4000,"queueAbandonEnabled":%s}"""
                .formatted(enabled));
        var time=new V5EngineTest.Time();var calls=new ArrayList<String>();var started=time.instant();var saleEnd=started.plus(config.realDuration(3600));
        HttpTransport transport=request -> {
            String path=request.path().split("\\?")[0];
            synchronized(calls) { calls.add(request.method()+" "+path); }
            time.nanos.addAndGet(1_000_000);boolean ended=!time.instant().isBefore(saleEnd);
            return switch(path) {
                case "/admin/stats" -> {
                    var batches=new java.util.HashMap<String,Object>();batches.put("nextReleaseAt",null);batches.put("open",0);
                    yield new HttpTransport.Response(200,Map.of("saleEndAt",saleEnd.toString(),"releaseBatches",batches,
                            "reservations",Map.of("HELD",0,"CONFIRMING",0,"PENDING_DEPOSIT",0),"counters",Map.of("reopenCount",0,"immediateReturns",0),
                            "seats",Map.of("SOLD",0,"byGrade",Map.of())));
                }
                case "/queue/enter","/queue/status" -> ended ? new HttpTransport.Response(200,Map.of("token","t","status","CLOSED","reason","SALE_ENDED","position",0,"pollAfterMs",1000))
                        : new HttpTransport.Response(200,Map.of("token","t","status","WAITING","position",900,"pollAfterMs",4000));
                default -> new HttpTransport.Response(200,Map.of());
            };
        };
        var summary=new RunEngine(config,transport,time).run();
        synchronized(calls) { return new EngineRun(summary,List.copyOf(calls)); }
    }

    /** 실행 엔진: 멈춘 줄에서 떠나면 /queue/leave → 재방문 대기 → 판매 종료 때 gaveUp. */
    @Test void engineLeavesTheQueueAndCountsIt() {
        var run=engineRun(true);var summary=run.summary();var calls=run.calls();
        assertThat(calls).contains("POST /queue/leave");
        assertThat(summary.outcomes()).containsEntry("queueAbandoned",1L).containsEntry("gaveUp",1L).containsEntry("incomplete",0L);
        assertThat(summary.events().get("queueAbandons")).isEqualTo(1L);
        assertThat(summary.events().get("queueAbandonsStalled")).isBetween(0L,1L);
        assertThat(summary.outcomesByChurn().get("casual")).containsEntry("queueAbandoned",1L);
        // 떠난 뒤에는 순번 조회를 더 보내지 않는다
        int leave=calls.indexOf("POST /queue/leave");
        assertThat(calls.subList(leave,calls.size())).doesNotContain("GET /queue/status");
    }
    /** 꺼짐: 대기 이탈 0, 판매 종료까지 순번 조회만 하고 매진 퇴장 (이 규칙 전 동작). */
    @Test void engineWithTheRuleOffKeepsWaitingUntilTheSaleEnds() {
        var run=engineRun(false);var summary=run.summary();
        assertThat(run.calls()).doesNotContain("POST /queue/leave");
        assertThat(summary.outcomes()).containsEntry("queueAbandoned",0L).containsEntry("soldOut",1L).containsEntry("gaveUp",0L);
        assertThat(summary.events()).containsEntry("queueAbandons",0L).containsEntry("queueAbandonsStalled",0L);
        // 판매 종료(실제 900초)까지 4초마다 조회: 약 225번
        assertThat(run.calls().stream().filter("GET /queue/status"::equals).count()).isBetween(220L,230L);
    }
}
