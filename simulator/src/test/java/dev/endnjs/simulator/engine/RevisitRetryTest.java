package dev.endnjs.simulator.engine;

import java.util.*;
import dev.endnjs.simulator.http.JsonCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 재방문 대기 중 재시도: hardcore 새로고침×2 ±20%, persistent U(10,20)초 + 반감 중단, casual 없음, 별도 난수, 매진 중 예약 서버 요청 0. */
class RevisitRetryTest {
    private final JsonCodec json=new JsonCodec();
    private RunConfig config(String extra) { return json.config("{\"timeScale\":1"+extra+"}"); }

    @Test void hardcoreIntervalIsPersonaRefreshTimesTwoWithinTwentyPercent() {
        var config=config("");
        for(var persona:List.of(Persona.FAST,Persona.NORMAL,Persona.SLOW)) {
            double base=config.personas().get(persona.key()).refreshSec()*2000; // fast 2초, normal 4초, slow 6초
            var samples=new ArrayList<Long>();
            for(int i=0;i<2000;i++) samples.add(RevisitRetry.forUser(config,i,ChurnPolicy.Type.hardcore,persona).nextIntervalSimMillis());
            assertThat(samples).allSatisfy(ms -> assertThat(ms).isBetween(Math.round(base*.8),Math.round(base*1.2)));
            assertThat(samples.stream().mapToLong(Long::longValue).average().orElseThrow()).isCloseTo(base,within(base*.02));
            assertThat(Collections.min(samples)).isLessThan(Math.round(base*.85));assertThat(Collections.max(samples)).isGreaterThan(Math.round(base*1.15)); // 편차가 실제로 있음
        }
        assertThat(config.personas().get("fast").refreshSec()*2).isEqualTo(2.0);
    }
    @Test void persistentIntervalIsTenToTwentySecondsAndItGivesUpWithTheHalfLife() {
        var config=config("");
        var retry=RevisitRetry.forUser(config,1,ChurnPolicy.Type.persistent,Persona.NORMAL);
        for(int i=0;i<500;i++) assertThat(retry.nextIntervalSimMillis()).isBetween(10_000L,20_000L);
        // 180초(반감기) 동안 시도하면 약 절반이 그만둔다. 4,000명, 허용 오차 ±0.03
        int quit=0;
        for(int i=0;i<4000;i++) {
            var user=RevisitRetry.forUser(config,i,ChurnPolicy.Type.persistent,Persona.NORMAL);double elapsed=0;
            while(elapsed<180 && user.active()) { double step=Math.min(15,180-elapsed);elapsed+=step;user.quitsBeforeAttempt(step); }
            if(!user.active()) quit++;
        }
        assertThat(quit/4000.0).isCloseTo(.5,within(.03));
    }
    @Test void hardcoreNeverGivesUpAndCasualNeverRetries() {
        var config=config("");
        var hardcore=RevisitRetry.forUser(config,0,ChurnPolicy.Type.hardcore,Persona.FAST);
        for(int i=0;i<1000;i++) assertThat(hardcore.quitsBeforeAttempt(1000)).isFalse();
        assertThat(hardcore.active()).isTrue();
        assertThat(RevisitRetry.forUser(config,0,ChurnPolicy.Type.casual,Persona.FAST).active()).isFalse();
    }
    @Test void offMeansNoRetryAndRecordsWithoutTheFieldAreOff() {
        assertThat(RevisitRetry.forUser(config(",\"revisitRetryEnabled\":false"),0,ChurnPolicy.Type.hardcore,Persona.FAST).active()).isFalse();
        assertThat(RunConfig.defaults().revisitRetryEnabled()).isTrue();assertThat(config("").revisitRetryEnabled()).isTrue();
    }
    @Test void decisionsUseASeparateStreamSoProfilesStayTheSame() {
        var config=config("");
        for(int i=0;i<50;i++) {
            var a=VirtualUser.profile(config,i);var b=VirtualUser.profile(config,i);
            var retry=RevisitRetry.forUser(config,i,a.churn(),a.persona());
            for(int n=0;n<20;n++) { retry.nextIntervalSimMillis();retry.quitsBeforeAttempt(15); }
            assertThat(a.random().nextLong()).isEqualTo(b.random().nextLong());
        }
        assertThat(RevisitRetry.stream(config,0).nextLong()).isNotEqualTo(QueueAbandon.stream(config,0).nextLong());
    }

    /** 실행 엔진: 매진(대기열 CLOSED SOLD_OUT) 중 재방문 대기 사용자 1명. */
    private record EngineRun(Summary summary,List<String> calls) {}
    private EngineRun engineRun(String churn,boolean enabled) {
        var config=json.config("""
                {"users":1,"rows":1,"cols":4,"grades":[{"name":"VIP","rows":1,"price":100}],"arrival":[{"percent":100,"fromSec":0,"toSec":0}],
                 "personaMix":{"fast":100,"normal":0,"slow":0},"churnMix":{"casual":%d,"persistent":%d,"hardcore":%d},
                 "timeScale":4,"saleDurationSec":120,"timeLimitSec":400,"revisitRetryEnabled":%s}"""
                .formatted(churn.equals("casual") ? 100 : 0,churn.equals("persistent") ? 100 : 0,churn.equals("hardcore") ? 100 : 0,enabled));
        var time=new V5EngineTest.Time();var calls=new ArrayList<String>();var started=time.instant();var saleEnd=started.plus(config.realDuration(120));
        HttpTransport transport=request -> {
            String path=request.path().split("\\?")[0];
            synchronized(calls) { calls.add(request.method()+" "+path); }
            time.nanos.addAndGet(1_000_000);boolean ended=!time.instant().isBefore(saleEnd);
            return switch(path) {
                case "/admin/stats" -> {
                    var batches=new HashMap<String,Object>();batches.put("nextReleaseAt",null);batches.put("open",0);
                    yield new HttpTransport.Response(200,Map.of("saleEndAt",saleEnd.toString(),"releaseBatches",batches,
                            "reservations",Map.of("HELD",0,"CONFIRMING",0,"PENDING_DEPOSIT",0),"counters",Map.of("reopenCount",0,"immediateReturns",0),
                            "seats",Map.of("SOLD",4,"byGrade",Map.of())));
                }
                case "/queue/enter" -> new HttpTransport.Response(200,Map.of("token","t","status","CLOSED","reason",ended ? "SALE_ENDED" : "SOLD_OUT","position",0,"pollAfterMs",1000));
                default -> new HttpTransport.Response(200,Map.of());
            };
        };
        var summary=new RunEngine(config,transport,time).run();
        synchronized(calls) { return new EngineRun(summary,List.copyOf(calls)); }
    }
    @Test void hardcoreRetriesDuringSoldOutOnlyKnockOnTheQueue() {
        var run=engineRun("hardcore",true);
        long enters=run.calls().stream().filter("POST /queue/enter"::equals).count();
        // 판매 120초 동안 약 2초(fast)마다: 첫 진입 + 약 60회
        assertThat(run.summary().events().get("revisitRetries")).isBetween(45L,75L);
        assertThat(enters).isEqualTo(1+run.summary().events().get("revisitRetries"));
        assertThat(run.summary().events()).containsEntry("revisitRetryAdmitted",0L);
        assertThat(run.calls()).noneMatch(c -> c.contains("/seats") || c.contains("/holds")); // 매진 중 시도는 예약 서버 요청 0건
        assertThat(run.summary().outcomes()).containsEntry("soldOut",1L);
    }
    @Test void casualAndOffDoNotRetry() {
        for(var run:List.of(engineRun("casual",true),engineRun("hardcore",false))) {
            assertThat(run.summary().events()).containsEntry("revisitRetries",0L);
            assertThat(run.calls().stream().filter("POST /queue/enter"::equals).count()).isEqualTo(1);
        }
    }
}
