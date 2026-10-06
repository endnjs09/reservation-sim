package dev.endnjs.simulator.engine;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import dev.endnjs.simulator.http.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static dev.endnjs.simulator.engine.HttpTransport.Target.*;

class V5EngineTest {
    private final JsonCodec json=new JsonCodec();
    private RunConfig config(Map<String,Object> changes) {
        var values=new java.util.LinkedHashMap<String,Object>(Map.ofEntries(
                Map.entry("rows",1),Map.entry("cols",4),Map.entry("grades",List.of(Map.of("name","VIP","rows",1,"price",100))),
                Map.entry("users",1),Map.entry("arrival",List.of(Map.of("percent",100,"fromSec",0,"toSec",0))),
                Map.entry("personaMix",Map.of("fast",100,"normal",0,"slow",0)),
                Map.entry("personas",Map.of("fast",Map.of("selectSec",Map.of("min",0,"max",0),"authSec",Map.of("min",0,"max",0),"refreshSec",1))),
                Map.entry("priceStepSec",Map.of("fast",Map.of("min",0,"max",0))),
                Map.entry("ticketCountMix",Map.of("1",0,"2",100,"3",0,"4",0)),Map.entry("paymentMix",Map.of("card",100,"deposit",0)),
                Map.entry("abandonRate",0),Map.entry("cancelAfterPurchaseRate",0),Map.entry("timeScale",1),Map.entry("timeLimitSec",100),
                Map.entry("saleDurationSec",20),Map.entry("depositDeadlineSec",4),Map.entry("returnDelaySec",4)));
        values.putAll(changes);return json.config(json.encode(values));
    }
    private Summary run(Fake fake) { var summary=new RunEngine(fake.config,fake,fake.time).run();fake.empty();assertThat(summary.outcomes().get("error")).isZero();return summary; }
    @ParameterizedTest @ValueSource(ints={1,2,4})
    void groupCardPurchaseScalesAllUserTimingButKeepsPgMsAndHttpTimeout(int scale) {
        var config=config(Map.of("timeScale",scale,"priceStepSec",Map.of("fast",Map.of("min",4,"max",4)),
                "personas",Map.of("fast",Map.of("selectSec",Map.of("min",2,"max",2),"authSec",Map.of("min",6,"max",6)))));
        var fake=new Fake(config);fake.setup().admitted().available().held().checkout().auth().confirmed();var summary=run(fake);
        assertThat(summary.durationMs()).isBetween(12000L/scale,12030L/scale+30);
        var hold=fake.call("/holds");assertThat(hold.request.body()).containsKey("seatIds").doesNotContainKey("seatId");
        assertThat((List<?>)hold.request.body().get("seatIds")).hasSize(2);
        var auth=fake.call("/auth");assertThat(auth.request.body().get("amount")).isEqualTo(200L);
        assertThat(hold.request.timeout()).isEqualTo(java.time.Duration.ofSeconds(10));
        assertThat(fake.calls.stream().filter(call->call.request.target()==HttpTransport.Target.SERVER).findFirst().orElseThrow().request.body()).containsEntry("maxSeatsPerUser",4).containsEntry("depositDeadlineSec",4).containsEntry("returnDelaySec",4);
        assertThat(summary.outcomes()).containsEntry("confirmed",1L).containsEntry("depositPaid",0L);
        assertThat(summary.seatsSold()).isEqualTo(2);assertThat(summary.seatsByGrade()).containsEntry("VIP",2L);
    }
    @Test void groupConflictRetriesWholeGroupWithNewSelectionAndIdempotencyKey() {
        var fake=new Fake(config(Map.of()));fake.setup().admitted().available(1,2)
                .step("POST","/holds",409,Map.of("code","SEAT_UNAVAILABLE","unavailableSeatIds",List.of(1)))
                .available(3,4).held().checkout().auth().confirmed();var summary=run(fake);
        var holds=fake.calls.stream().filter(c -> c.request.path().equals("/holds")).toList();
        assertThat(holds.getFirst().request.body().get("seatIds")).isEqualTo(List.of(1L,2L));
        assertThat(holds.getLast().request.body().get("seatIds")).isEqualTo(List.of(3L,4L));
        assertThat(holds.getFirst().request.headers().get("Idempotency-Key")).isNotEqualTo(holds.getLast().request.headers().get("Idempotency-Key"));
        assertThat(summary.events().get("conflicts")).isEqualTo(1);
    }
    @Test void tooFewAvailableSeatsDoesNotApplyZeroInventoryChurn() {
        var fake=new Fake(config(Map.of("churnMix",Map.of("casual",100,"persistent",0,"hardcore",0),"casualLeaveSec",Map.of("min",0,"max",0))));
        fake.setup().admitted().available(1).available(1,2).held().checkout().auth().confirmed();var summary=run(fake);
        assertThat(summary.requests().byEndpoint()).containsEntry("seats",2L).containsEntry("queue.leave",0L);
        assertThat(summary.outcomes().get("confirmed")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(ints={1,2,4})
    void depositPaymentAndBankFollowUpWorkAfterSaleEndWithoutTokenOrPg(int scale) {
        var fake=new Fake(config(Map.of("timeScale",scale,"saleDurationSec",1,"depositDeadlineSec",40,
                "paymentMix",Map.of("card",0,"deposit",100),"depositNoPayRate",0)));
        fake.setup().admitted().available().held().deposit().paid();var summary=run(fake);
        var pay=fake.call("/deposits/1/pay");assertThat(pay.at).isAfter(fake.started.plusSeconds(1/scale));
        assertThat(pay.request.headers()).doesNotContainKey("X-Admission-Key");assertThat(pay.request.body()).containsEntry("amount",200L);
        assertThat(summary.requests().byEndpoint()).containsEntry("auth",0L).containsEntry("checkout",0L).containsEntry("deposit",1L).containsEntry("deposit.pay",1L);
        assertThat(summary.outcomes()).containsEntry("confirmed",1L).containsEntry("depositPaid",1L);
        assertThat(summary.seatsSold()).isEqualTo(2);
        assertThat(summary.requests().sent()).isEqualTo(fake.userCalls());
    }
    @Test void noPaySendsNoBankRequestAndWaitsForActualExpiryAndBatchReopening() {
        var fake=new Fake(config(Map.of("paymentMix",Map.of("card",0,"deposit",100),"depositNoPayRate",1,"revisitProb",Map.of("casual",1,"persistent",1,"hardcore",1))));
        fake.setup().admitted().available().held().deposit();var summary=run(fake);
        assertThat(summary.outcomes()).containsEntry("gaveUp",1L).containsEntry("depositExpired",1L).containsEntry("depositPaid",0L);
        assertThat(summary.requests().byEndpoint()).containsEntry("deposit.pay",0L).containsEntry("queue.enter",1L);
        // 입금 기한까지 예약 상태를 확인하는 GET /reservations/{id}는 사용자 요청으로 센다 (서버 reservation 집계와 같은 기준, docs/DECISION_CLAUDE.md)
        long polls=fake.calls.stream().filter(c -> c.request().method().equals("GET") && c.request().path().equals("/reservations/1")).count();
        assertThat(polls).isPositive();assertThat(summary.requests().byEndpoint()).containsEntry("reservation",polls);
        assertThat(((Number)RunStore_object(summary.clientLatencyMs().get("reservation")).get("count")).longValue()).isEqualTo(polls);
        assertThat(summary.events()).containsEntry("reopenSeen",1L);
        assertThat(summary.seatsSold()).isZero();assertThat(fake.reservation).isEqualTo("DEPOSIT_EXPIRED");
        assertThat(summary.requests().sent()).isEqualTo(fake.userCalls());
    }
    @ParameterizedTest @ValueSource(strings={"card","deposit"})
    void confirmedPurchaseCanCancelWithoutTokenAndCurrentSoldSeatsDropToZero(String method) {
        var fake=new Fake(config(Map.of("paymentMix",Map.of("card",method.equals("card") ? 100 : 0,"deposit",method.equals("deposit") ? 100 : 0),
                "depositNoPayRate",0,"cancelAfterPurchaseRate",1)));
        fake.setup().admitted().available().held();
        if(method.equals("card")) fake.checkout().auth().confirmed();else fake.deposit().paid();
        fake.canceled();var summary=run(fake);
        assertThat(fake.call("/reservations/1/cancel").request.headers()).doesNotContainKey("X-Admission-Key");
        assertThat(summary.outcomes()).containsEntry("confirmed",1L).containsEntry("canceledAfterPurchase",1L);
        assertThat(summary.seatsSold()).isZero();assertThat(summary.events().get("immediateReturnsSeen")).isEqualTo(2);
        assertThat(summary.outcomesByChurn().get(VirtualUser.profile(fake.config,0).churn().name()).get("canceledAfterPurchase")).isEqualTo(1);
        // 끝까지 간 실행(기준선)에는 취소 대기가 남지 않는다
        assertThat(summary.events()).containsEntry("cancelPendingAtStop",0L);
    }
    /** 예매(카드 확정·입금 완료) 뒤 취소 시점을 기다리다 실행이 멈추면: 결과는 confirmed, 실행 안 된 취소는 events.cancelPendingAtStop. */
    @ParameterizedTest @ValueSource(strings={"card","deposit"})
    void stopWhileWaitingToCancelKeepsTheConfirmedPurchase(String method) {
        var fake=new Fake(config(Map.of("paymentMix",Map.of("card",method.equals("card") ? 100 : 0,"deposit",method.equals("deposit") ? 100 : 0),
                "depositNoPayRate",0,"cancelAfterPurchaseRate",1,"saleDurationSec",600,"timeLimitSec",2)));
        fake.setup().admitted().available().held();
        if(method.equals("card")) fake.checkout().auth().confirmed();else fake.deposit().paid();
        var summary=run(fake);
        assertThat(fake.calls.stream().noneMatch(c -> c.request().path().equals("/reservations/1/cancel"))).isTrue();
        assertThat(summary.outcomes()).containsEntry("confirmed",1L).containsEntry("incomplete",0L).containsEntry("canceledAfterPurchase",0L);
        assertThat(summary.events()).containsEntry("cancelPendingAtStop",1L);
        assertThat(fake.reservation).isEqualTo("CONFIRMED"); // 서버 쪽도 확정 그대로: summary confirmed = 서버 CONFIRMED
        assertThat(summary.outcomesByChurn().get(VirtualUser.profile(fake.config,0).churn().name()).get("confirmed")).isEqualTo(1);
    }
    @Test void finishingPendingUsersAtStopTurnsOnlyCancelWaitIntoConfirmed() {
        var config=config(Map.of("users",3));var stats=new RunStats(config,new Time(),java.util.Collections.nCopies(3,Persona.FAST));
        stats.state(0,RunStats.UserState.cancel_wait);stats.state(1,RunStats.UserState.holding);stats.state(2,RunStats.UserState.cancel_wait);
        stats.finish(2,RunStats.Outcome.confirmed); // 취소 시도까지 마친 사용자: 이미 확정, 다시 세지 않음
        stats.finishPending(RunStats.Outcome.incomplete);
        assertThat(stats.summary().outcomes()).containsEntry("confirmed",2L).containsEntry("incomplete",1L);
        assertThat(stats.summary().events()).containsEntry("cancelPendingAtStop",1L);
    }
    @Test void cancellation502RetriesWithoutChangingUserOutcomeOrDoubleCounting() {
        var fake=new Fake(config(Map.of("cancelAfterPurchaseRate",1)));fake.setup().admitted().available().held().checkout().auth().confirmed()
                .step("POST","/reservations/1/cancel",502,Map.of("code","PG_UNAVAILABLE")).canceled();
        var summary=run(fake);assertThat(summary.requests().byEndpoint().get("reservation.cancel")).isEqualTo(2);
        assertThat(summary.outcomes().get("canceledAfterPurchase")).isEqualTo(1);assertThat(summary.responses().get("5xx")).isEqualTo(1);
    }
    @Test void abandonmentReleasesAdmissionButKeepsHoldUntilRealServerExpiry() {
        var fake=new Fake(config(Map.of("abandonRate",1,"holdTtlSec",4)));fake.setup().admitted().available().held()
                .step("POST","/queue/leave",200,Map.of("status","LEFT"));var summary=run(fake);
        assertThat(summary.outcomes().get("abandoned")).isEqualTo(1);assertThat(summary.requests().byEndpoint().get("auth")).isZero();
        assertThat(summary.events().get("immediateReturnsSeen")).isEqualTo(2);assertThat(summary.durationMs()).isGreaterThanOrEqualTo(4000);
    }
    @Test void queueEnter409SaleEndedIsNormalExitWithNoSeatRequest() {
        var fake=new Fake(config(Map.of()));fake.setup().step("POST","/queue/enter",409,Map.of("code","SALE_ENDED"));var summary=run(fake);
        assertThat(summary.outcomes().get("soldOut")).isEqualTo(1);assertThat(summary.requests().byEndpoint().get("seats")).isZero();
    }
    @Test void deadlineInterruptsOutstandingBankWorkAndKeepsIncompleteExitTwo() {
        var fake=new Fake(config(Map.of("paymentMix",Map.of("card",0,"deposit",100),"depositNoPayRate",1,"depositDeadlineSec",30,
                "timeLimitSec",2,"timeScale",4)));
        fake.setup().admitted().available().held().deposit();var summary=run(fake);
        assertThat(summary.outcomes().get("incomplete")).isEqualTo(1);assertThat(summary.exitCode()).isEqualTo(2);
        assertThat(summary.durationMs()).isBetween(500L,510L);assertThat(summary.requests().byEndpoint().get("deposit.pay")).isZero();
    }
    @Test void duplicateUserFinalizationAndMilestonesDoNotInflateUserOrChurnCounts() {
        var config=config(Map.of());var fake=new Fake(config);var stats=new RunStats(config,fake.time,List.of(Persona.FAST));
        stats.milestone(0,RunStats.Milestone.depositPaid);stats.milestone(0,RunStats.Milestone.depositPaid);
        stats.finish(0,RunStats.Outcome.confirmed);stats.finish(0,RunStats.Outcome.error);
        assertThat(stats.summary().outcomes()).containsEntry("confirmed",1L).containsEntry("error",0L).containsEntry("depositPaid",1L);
        assertThat(stats.summary().outcomesByChurn().values().stream().mapToLong(m -> m.get("confirmed")).sum()).isEqualTo(1);
        assertThat(stats.sampleLive(false).usersByChurn().values().stream().mapToLong(m -> m.get("done")).sum()).isEqualTo(1);
    }
    @Test void missingFinalAdminInventoryIsReportedUnknown() {
        var fake=new Fake(config(Map.of()));fake.adminUnavailable=true;fake.setup().admitted().available().held().checkout().auth().confirmed();
        var summary=run(fake);assertThat(summary.seatsSold()).isNull();assertThat(summary.seatsByGrade()).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void scheduledRevisitRemainsValidAfterRealReopenButNotAfterDiscardedBatch(boolean reopened) throws Exception {
        var config=config(Map.of());var time=new Time();var control=new RunControl(time);control.start(100);
        var stats=new RunStats(config,time,List.of(Persona.FAST));var release=time.instant().plusSeconds(2);
        HttpTransport transport=request -> {
            var batches=new java.util.LinkedHashMap<String,Object>();
            boolean due=!time.instant().isBefore(release);batches.put("nextReleaseAt",due ? null : release.toString());
            return new HttpTransport.Response(200,Map.of("saleEndAt",Instant.parse("2026-10-03T05:00:20Z").toString(),
                    "releaseBatches",batches,"counters",Map.of("reopenCount",due && reopened ? 1 : 0,"immediateReturns",0)));
        };
        var schedule=new RevisitSchedule(new RunHttp(transport,control,stats),time,config,stats);schedule.start(time.instant());
        assertThat(schedule.signal().releaseAt()).isEqualTo(release);assertThat(schedule.visitable(release)).isTrue();
        time.sleep(3000);assertThat(schedule.signal().releaseAt()).isNull();
        assertThat(schedule.visitable(release)).isEqualTo(reopened);
        assertThat(stats.summary().events().get("reopenSeen")).isEqualTo(reopened ? 1L : 0L);
        assertThat(stats.summary().requests().sent()).isZero();
    }

    private static Map<?,?> RunStore_object(Object value) { return value instanceof Map<?,?> map ? map : Map.of(); }
    static final class Time implements RunTime {
        final AtomicLong nanos=new AtomicLong();
        public Instant instant() { return Instant.parse("2026-10-03T05:00:00Z").plusNanos(nanos.get()); }
        public long nanoTime() { return nanos.get(); }
        public void sleep(long millis) { nanos.addAndGet(millis*1_000_000L); }
    }
    static final class Fake implements HttpTransport {
        record Step(String method,String path,Response response) {}
        record Call(Request request,Instant at) {}
        final Time time=new Time();final Instant started=time.instant();final RunConfig config;
        final ArrayDeque<Step> steps=new ArrayDeque<>();final List<Call> calls=new ArrayList<>();
        String reservation="NONE";int quantity=2,sold;Instant deadline,holdDeadline,releaseAt;long immediate,reopened;
        boolean adminUnavailable;
        Fake(RunConfig config) { this.config=config; }
        Fake step(String method,String path,int status,Map<String,Object> body) { steps.add(new Step(method,path,new Response(status,body)));return this; }
        Fake setup() { return step("POST","/admin/reset",200,Map.of()).step("POST","/admin/reset",200,Map.of()).step("POST","/admin/reset",200,Map.of()).step("PUT","/admin/config",200,Map.of()); }
        Fake admitted() { return step("POST","/queue/enter",200,Map.of("token","token","status","ADMITTED","admissionKey","key")); }
        Fake available(long... ids) {
            if(ids.length==0) ids=new long[]{1,2,3,4};
            var seats=java.util.Arrays.stream(ids).mapToObj(id -> dev.endnjs.simulator.SeatsContract.seat(id,"A"+id,"AVAILABLE")).toList();
            return step("GET","/seats",200,dev.endnjs.simulator.SeatsContract.response(4,seats));
        }
        Fake held() { return step("POST","/holds",201,Map.of("reservationId",1)); }
        Fake checkout() { return step("POST","/holds/1/checkout",200,Map.of("orderId","order-1","amount",200)); }
        Fake auth() { return step("POST","/auth",200,Map.of("paymentKey","key")); }
        Fake confirmed() { return step("POST","/payments/confirm",200,Map.of("status","CONFIRMED")); }
        Fake deposit() { return step("POST","/holds/1/deposit",200,Map.of("status","PENDING_DEPOSIT","amount",200)); }
        Fake paid() { return step("POST","/deposits/1/pay",200,Map.of("status","CONFIRMED")); }
        Fake canceled() { return step("POST","/reservations/1/cancel",200,Map.of("status","CANCELED")); }
        synchronized void update() {
            if(reservation.equals("PENDING_DEPOSIT") && !time.instant().isBefore(deadline)) {
                reservation="DEPOSIT_EXPIRED";releaseAt=time.instant().plus(config.realDuration(config.returnDelaySec()));
            }
            if(reservation.equals("HELD") && !time.instant().isBefore(holdDeadline)) { reservation="EXPIRED";immediate+=quantity; }
            if(releaseAt!=null && !time.instant().isBefore(releaseAt)) { releaseAt=null;reopened++; }
        }
        public synchronized Response exchange(Request request) {
            calls.add(new Call(request,time.instant()));update();
            if(request.path().equals("/admin/stats")) {
                if(adminUnavailable) return new Response(503,Map.of());
                var batches=new java.util.LinkedHashMap<String,Object>();batches.put("nextReleaseAt",releaseAt==null ? null : releaseAt.toString());batches.put("open",releaseAt==null ? 0 : 1);
                return new Response(200,Map.of("saleEndAt",started.plus(config.realDuration(config.saleDurationSec())).toString(),"releaseBatches",batches,
                        "reservations",Map.of("HELD",reservation.equals("HELD") ? 1 : 0,"CONFIRMING",0,"PENDING_DEPOSIT",reservation.equals("PENDING_DEPOSIT") ? 1 : 0),
                        "seats",Map.of("SOLD",sold,"byGrade",Map.of("VIP",Map.of("SOLD",sold))),"counters",Map.of("immediateReturns",immediate,"reopenCount",reopened)));
            }
            if(request.path().equals("/reservations/1") && (steps.isEmpty() || !steps.getFirst().path.equals(request.path()))) return new Response(200,Map.of("status",reservation));
            assertThat(steps).describedAs("Request: "+request).isNotEmpty();var step=steps.removeFirst();
            assertThat(request.method()).isEqualTo(step.method);assertThat(request.path()).isEqualTo(step.path);
            time.nanos.addAndGet(1_000_000);
            if(step.response.ok()) {
                switch(request.path()) {
                    case "/holds" -> { quantity=((List<?>)request.body().get("seatIds")).size();reservation="HELD";holdDeadline=time.instant().plus(config.realDuration(config.holdTtlSec())); }
                    case "/holds/1/deposit" -> {
                        reservation="PENDING_DEPOSIT";deadline=time.instant().plus(config.realDuration(config.depositDeadlineSec()));
                        var body=new java.util.LinkedHashMap<>(step.response.body());body.put("depositDeadline",deadline.toString());return new Response(200,body);
                    }
                    case "/deposits/1/pay","/payments/confirm" -> { reservation="CONFIRMED";sold=quantity; }
                    case "/reservations/1/cancel" -> { reservation="CANCELED";sold=0;immediate+=quantity; }
                }
            }
            return step.response;
        }
        Call call(String path) { return calls.stream().filter(c -> c.request.path().equals(path)).findFirst().orElseThrow(); }
        long userCalls() { return calls.stream().filter(c -> !c.request.path().startsWith("/admin/")).count(); }
        void empty() { assertThat(steps).isEmpty(); }
    }
}
