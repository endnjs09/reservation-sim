package dev.endnjs.simulator.engine;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import dev.endnjs.simulator.http.JsonCodec;
import org.junit.jupiter.api.Test;
import static dev.endnjs.simulator.engine.HttpTransport.Target.*;
import static org.assertj.core.api.Assertions.*;

class S5EngineTest {
    private static final String TOKEN = "2d7b8990-1be4-4ad2-a4dc-58d2c8a965ea";
    private final JsonCodec json = new JsonCodec();
    private RunConfig config(Map<String, Object> changes) {
        var base = new java.util.LinkedHashMap<String, Object>(Map.ofEntries(Map.entry("rows", 1), Map.entry("cols", 2),
                Map.entry("grades", List.of(Map.of("name", "VIP", "rows", 1, "price", 120000))), Map.entry("users", 1),
                Map.entry("arrival", List.of(Map.of("percent", 100, "fromSec", 0, "toSec", 0))),
                Map.entry("personaMix", Map.of("fast", 100, "normal", 0, "slow", 0)),
                Map.entry("personas", Map.of("fast", Map.of("selectSec", Map.of("min", 0, "max", 0),
                        "authSec", Map.of("min", 0, "max", 0), "refreshSec", .01))),
                Map.entry("timeScale",1),Map.entry("ticketCountMix",Map.of("1",100,"2",0,"3",0,"4",0)),
                Map.entry("paymentMix",Map.of("card",100,"deposit",0)),Map.entry("cancelAfterPurchaseRate",0),
                Map.entry("priceStepSec",Map.of("fast",Map.of("min",0,"max",0))),Map.entry("abandonRate", 0), Map.entry("timeLimitSec", 60)));
        base.putAll(changes);
        return json.config(json.encode(base));
    }

    @Test void frontRowsAndCenterWeightsMatchLargeSampleDistribution() {
        var seats = new ArrayList<SeatPicker.Seat>();
        for (int id = 1; id <= 30; id++) seats.add(new SeatPicker.Seat(id, "seat-" + id, "AVAILABLE"));
        var picker = new SeatPicker(); var random = new SplittableRandom(42);
        int[] rows = new int[3]; int[] columns = new int[10]; int samples = 200000;
        for (int i = 0; i < samples; i++) {
            var seat = picker.pick(seats, 10, random).orElseThrow();
            rows[(int) (seat.id() - 1) / 10]++; columns[(int) (seat.id() - 1) % 10]++;
        }
        assertThat(rows[0] / (double) samples).isCloseTo(.8, within(.005));
        assertThat(rows[2]).isZero();
        int[] weights = {1,2,3,4,5,5,4,3,2,1};
        for (int i = 0; i < 10; i++) assertThat(columns[i] / (double) samples).isCloseTo(weights[i] / 30.0, within(.005));
    }
    @Test void pickerSkipsEmptyRowsAndUsesOnlyAvailableCandidatesIncludingOddWidth() {
        var seats = List.of(new SeatPicker.Seat(1,"A1","HELD"), new SeatPicker.Seat(2,"A2","SOLD"),
                new SeatPicker.Seat(7,"C3","AVAILABLE"), new SeatPicker.Seat(13,"E1","AVAILABLE"));
        var random = new SplittableRandom(42); var picker = new SeatPicker(); int front = 0;
        for (int i = 0; i < 10000; i++) if (picker.pick(seats, 3, random).orElseThrow().id() == 7) front++;
        assertThat(front / 10000.0).isCloseTo(.8, within(.02));
        assertThat(picker.pick(List.of(), 3, random)).isEmpty();
        assertThat(picker.pick(List.of(seats.get(2)), 3, random)).contains(seats.get(2));
    }
    @Test void defaultPersonasAndArrivalBandsHaveExpectedRatiosAndUniformMeans() {
        var config = RunConfig.defaults(); var random = new SplittableRandom(42);
        int samples = 200000; int[] bands = new int[3]; double[] sums = new double[3]; int[] personas = new int[3];
        for (int i = 0; i < samples; i++) {
            long millis = config.sampleArrivalMillis(random);
            int band = millis < 5000 ? 0 : millis < 60000 ? 1 : 2;
            bands[band]++; sums[band] += millis / 1000.0;
            personas[Persona.sample(config.personaMix(), random).ordinal()]++;
        }
        double[] arrival = {.6,.3,.1}, mix = {.2,.7,.1}, means = {2.5,32.5,180}, tolerance = {.05,.55,2.4};
        for (int i = 0; i < 3; i++) {
            assertThat(bands[i] / (double) samples).isCloseTo(arrival[i], within(.005));
            assertThat(personas[i] / (double) samples).isCloseTo(mix[i], within(.005));
            assertThat(sums[i] / bands[i]).isCloseTo(means[i], within(tolerance[i]));
        }
    }
    @Test void userSeedReproducesProfileAndPersonaTimeRanges() {
        var config = RunConfig.defaults();
        for (int i = 0; i < 1000; i++) {
            var first = VirtualUser.profile(config, i); var second = VirtualUser.profile(config, i);
            assertThat(first.persona()).isEqualTo(second.persona());
            assertThat(first.arrivalMillis()).isEqualTo(second.arrivalMillis());
            assertThat(first.random().nextLong()).isEqualTo(second.random().nextLong());
        }
        var random = new SplittableRandom(42);
        config.personas().values().forEach(timing -> {
            for (var range : List.of(timing.selectSec(), timing.authSec())) {
                double sum = 0;
                for (int i = 0; i < 10000; i++) {
                    long value = range.sampleMillis(random);
                    assertThat(value).isBetween(Math.round(range.min()*1000), Math.round(range.max()*1000)); sum += value;
                }
                assertThat(sum/10000).isCloseTo((range.min()+range.max())*500, within((range.max()-range.min())*10));
            }
        });
    }
    @Test void partialConfigKeepsDefaultsAndRejectsInvalidDistributionsAndUnknownFields() {
        assertThat(json.config("{}")).isEqualTo(RunConfig.defaults());
        assertThat(json.config("{\"targets\":{\"server\":\"http://localhost:18080\"}} ").targets().pg()).isEqualTo("http://localhost:8081");
        for (String invalid : List.of("{\"users\":0}", "{\"personaMix\":{\"fast\":99}}", "{\"authFailureRate\":2}",
                "{\"unknown\":1}", "{\"arrival\":[{\"percent\":99,\"fromSec\":0,\"toSec\":1}]}", "{\"rows\":2}")) {
            assertThatThrownBy(() -> json.config(invalid)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void successfulUserExcludesAdminRequestsAndMeasuresFullBodyLatency() {
        var fake = new Fake(); fake.setup().admitted().available().held().checkout().auth().confirmed();
        var summary = new RunEngine(config(Map.of()), fake, fake.time).run();
        fake.empty();
        assertThat(summary.requests().sent()).isEqualTo(6);
        assertThat(summary.responses()).containsEntry("2xx",6L).containsEntry("transportErrors",0L);
        assertThat(summary.avgLatencyMs().get("holds")).isEqualTo(4);
        assertThat(summary.avgLatencyMs().get("confirm")).isEqualTo(4);
        assertThat(summary.durationMs()).isEqualTo(40);
        assertThat(summary.outcomes()).containsEntry("confirmed",1L).containsEntry("error",0L);
        assertThat(summary.outcomesByPersona().get("fast").get("confirmed")).isEqualTo(1);
        assertThat(summary.exitCode()).isZero();
        var encoded = json.response(json.encode(summary));
        assertThat(encoded.keySet()).containsExactlyInAnyOrder("startedAt","durationMs","config","requests","responses",
                "avgLatencyMs","outcomes","outcomesByPersona","events","outcomesByChurn","seatsSold","seatsByGrade","signals","latencyMs","clientLatencyMs","errorClasses");
        assertThat(summary.requests().byEndpoint().get("auth")).isEqualTo(1);
        assertThat(fake.calls.stream().filter(call -> call.target()==SERVER).findFirst().orElseThrow().body()).containsEntry("timeScale", 1).containsEntry("saleDurationSec", 1200);
    }
    @Test void waitingPollAndConfirmingPollsLeadToConfirmedWithoutApprovalResubmission() {
        var fake = new Fake(); fake.setup().step(QUEUE,"POST","/queue/enter",200,Map.of("token",TOKEN,"status","WAITING","pollAfterMs",1000))
                .step(QUEUE,"GET","/queue/status?token="+TOKEN,200,Map.of("status","ADMITTED","admissionKey",TOKEN))
                .available().held().checkout().auth().step(SERVER,"POST","/payments/confirm",202,Map.of("status","CONFIRMING"))
                .step(SERVER,"GET","/reservations/1",200,Map.of("status","CONFIRMING"))
                .step(SERVER,"GET","/reservations/1",200,Map.of("status","CONFIRMED"));
        var engine = new RunEngine(config(Map.of()),fake,fake.time); var summary = engine.run(); fake.empty();
        assertThat(summary.requests().sent()).isEqualTo(9);
        assertThat(summary.requests().byEndpoint()).containsEntry("queue.status",1L).containsEntry("reservation",2L).containsEntry("confirm",1L);
        assertThat(summary.outcomes().get("confirmed")).isEqualTo(1);
        assertThat(summary.durationMs()).isEqualTo(3052);
        assertThat(engine.stats().sampleLive(false).users()).containsEntry("done",1L).containsEntry("confirming",0L);
    }
    @Test void admissionExpiryRequeuesAndUsesNewTokenForNextAttempt() {
        var fake = new Fake(); fake.setup().admitted().available().step(SERVER,"POST","/holds",403,Map.of("code","KEY_EXPIRED"))
                .leave().step(QUEUE,"POST","/queue/enter",200,Map.of("token","new-token","status","ADMITTED","admissionKey","new-token"))
                .available().held().checkout().auth().confirmed();
        var summary = new RunEngine(config(Map.of()),fake,fake.time).run(); fake.empty();
        assertThat(summary.events().get("requeues")).isEqualTo(1);
        assertThat(summary.responses().get("4xx")).isEqualTo(1);
        assertThat(summary.outcomes().get("confirmed")).isEqualTo(1);
        var holds = fake.calls.stream().filter(call -> call.path().equals("/holds")).toList();
        assertThat(holds.getLast().headers().get("X-Admission-Key")).isEqualTo("new-token");
        assertThat(holds.getFirst().headers().get("Idempotency-Key")).isNotEqualTo(holds.getLast().headers().get("Idempotency-Key"));
    }
    @Test void expiredWaitingTokenReentersAndClosedTokenEndsWithoutLeave() {
        var fake = new Fake(); fake.setup().step(QUEUE,"POST","/queue/enter",200,Map.of("token",TOKEN,"status","WAITING","pollAfterMs",1000))
                .step(QUEUE,"GET","/queue/status?token="+TOKEN,200,Map.of("status","EXPIRED"))
                .step(QUEUE,"POST","/queue/enter",200,Map.of("token","closed","status","CLOSED"));
        var summary = new RunEngine(config(Map.of()),fake,fake.time).run(); fake.empty();
        assertThat(summary.events().get("requeues")).isEqualTo(1);
        assertThat(summary.outcomes().get("soldOut")).isEqualTo(1);
        assertThat(summary.requests().byEndpoint().get("queue.leave")).isZero();
    }
    @Test void authenticationFailureReleasesAndRetriesThenConfirms() {
        var fake = new Fake(); fake.setup().admitted().available().held().checkout()
                .step(PG,"POST","/auth",400,Map.of("code","AUTH_FAILED"))
                .step(SERVER,"POST","/holds/1/release",200,Map.of("status","EXPIRED"))
                .available().held().checkout().auth().confirmed();
        var summary = new RunEngine(config(Map.of()),fake,fake.time).run(); fake.empty();
        assertThat(summary.events()).containsEntry("authFailed",1L).containsEntry("holdExpired",0L);
        assertThat(summary.requests().byEndpoint()).containsEntry("release",1L).containsEntry("auth",2L).containsEntry("holds",2L);
        assertThat(summary.outcomes().get("confirmed")).isEqualTo(1);
    }
    @Test void approvalDeclineRetriesAndRecoveredPaymentFailureUsesSameBranch() {
        var fake = new Fake(); fake.setup().admitted().available().held().checkout().auth()
                .step(SERVER,"POST","/payments/confirm",402,Map.of("code","PAYMENT_DECLINED"))
                .available().held().checkout().auth().step(SERVER,"POST","/payments/confirm",202,Map.of("status","CONFIRMING"))
                .step(SERVER,"GET","/reservations/1",200,Map.of("status","PAYMENT_FAILED"))
                .saleEnded();
        var summary = new RunEngine(config(Map.of()),fake,fake.time).run(); fake.empty();
        assertThat(summary.events().get("declined")).isEqualTo(2);
        assertThat(summary.outcomes().get("soldOut")).isEqualTo(1);
        assertThat(summary.responses().get("4xx")).isEqualTo(2);
    }
    @Test void expiredCheckoutSkipsAuthAndExpiredConfirmReturnsToBrowsing() {
        var fake = new Fake(); fake.setup().admitted().available().held()
                .step(SERVER,"POST","/holds/1/checkout",409,Map.of("code","RESERVATION_NOT_PAYABLE"))
                .available().held().checkout().auth()
                .step(SERVER,"POST","/payments/confirm",409,Map.of("code","RESERVATION_NOT_PAYABLE"))
                .saleEnded();
        var summary = new RunEngine(config(Map.of()),fake,fake.time).run(); fake.empty();
        assertThat(summary.events().get("holdExpired")).isEqualTo(2);
        assertThat(summary.requests().byEndpoint().get("auth")).isEqualTo(1);
        assertThat(summary.outcomes().get("soldOut")).isEqualTo(1);
    }
    @Test void abandonedUserLeavesQueueWithoutSendingPayment() {
        var fake = new Fake(); fake.setup().admitted().available().held().leave();
        var summary = new RunEngine(config(Map.of("abandonRate",1)),fake,fake.time).run(); fake.empty();
        assertThat(summary.outcomes().get("abandoned")).isEqualTo(1);
        assertThat(summary.requests().byEndpoint()).containsEntry("checkout",0L).containsEntry("queue.leave",1L).containsEntry("auth",0L);
    }
    @Test void conflictsWithAvailableSeatsDoNotApplyChurn() {
        var fake = new Fake(); fake.setup().admitted();
        for (int i = 0; i < 4; i++) fake.available().step(SERVER,"POST","/holds",409,Map.of("code","SEAT_UNAVAILABLE"));
        fake.available().held().checkout().auth().confirmed();
        var summary = new RunEngine(config(Map.of("casualLeaveSec",Map.of("min",0,"max",0))),fake,fake.time).run(); fake.empty();
        assertThat(summary.events().get("conflicts")).isEqualTo(4);
        assertThat(summary.outcomes().get("confirmed")).isEqualTo(1);
        var keys = new HashSet<String>();
        fake.calls.stream().filter(call -> call.path().equals("/holds")).forEach(call -> keys.add(call.headers().get("Idempotency-Key")));
        assertThat(keys).hasSize(5);
    }
    @Test void waitingNeverUsesChurnEvenWhenCasualDeadlineIsZero() {
        var fake = new Fake(); fake.setup().step(QUEUE,"POST","/queue/enter",200,Map.of("token",TOKEN,"status","WAITING","pollAfterMs",1000));
        for (int i=0;i<6;i++) fake.step(QUEUE,"GET","/queue/status?token="+TOKEN,200,Map.of("status","WAITING","pollAfterMs",1000));
        fake.step(QUEUE,"GET","/queue/status?token="+TOKEN,200,Map.of("status","ADMITTED","admissionKey",TOKEN))
                .available().held().checkout().auth().confirmed();
        var summary = new RunEngine(config(Map.of("churnMix",Map.of("casual",100,"persistent",0,"hardcore",0),
                "casualLeaveSec",Map.of("min",0,"max",0))),fake,fake.time).run(); fake.empty();
        assertThat(summary.outcomes().get("confirmed")).isEqualTo(1);
        assertThat(summary.requests().byEndpoint()).containsEntry("queue.status",7L).containsEntry("queue.leave",0L);
        assertThat(summary.requests().byEndpoint()).doesNotContainKey("admin.stats");
    }
    @Test void soldOutClosureJoinsDepartedPoolAndRevisitsWithoutCountingAdminReads() {
        var fake=new Fake(); fake.setup().step(QUEUE,"POST","/queue/enter",200,Map.of("token",TOKEN,"status","WAITING","pollAfterMs",1000))
                .step(QUEUE,"GET","/queue/status?token="+TOKEN,200,Map.of("status","CLOSED","reason","SOLD_OUT"));
        fake.step(SERVER,"GET","/admin/stats",200,Map.of("releaseBatches",Map.of("nextReleaseAt",fake.time.instant().plusSeconds(1).toString()),
                "saleEndAt",fake.time.instant().plusSeconds(30).toString()))
                .admitted().available().held().checkout().auth().confirmed();
        var summary=new RunEngine(config(Map.of("closeQueueOnSoldOut",true,"timeScale",1,
                "revisitProb",Map.of("casual",1,"persistent",1,"hardcore",1))),fake,fake.time).run(); fake.empty();
        assertThat(summary.outcomes().get("confirmed")).isEqualTo(1);
        assertThat(summary.requests().byEndpoint()).containsEntry("queue.enter",2L).containsEntry("queue.leave",0L).doesNotContainKey("admin.stats");
        assertThat(summary.requests().sent()).isEqualTo(fake.calls.stream().filter(call -> !call.path().startsWith("/admin/")).count());
        assertThat(summary.responses().get("2xx")).isEqualTo(summary.requests().sent());
    }
    @Test void casualLeavesAfterAdmittedMapIsEmptyAndCanRevisit() {
        var fake=new Fake(); fake.setup().admitted().step(SERVER,"GET","/seats",200,dev.endnjs.simulator.SeatsContract.response(2,List.of())).leave();
        fake.step(SERVER,"GET","/admin/stats",200,Map.of("releaseBatches",Map.of("nextReleaseAt",fake.time.instant().toString()),
                "saleEndAt",fake.time.instant().plusSeconds(30).toString())).admitted().available().held().checkout().auth().confirmed();
        var summary=new RunEngine(config(Map.of("churnMix",Map.of("casual",100,"persistent",0,"hardcore",0),
                "casualLeaveSec",Map.of("min",0,"max",0),"revisitProb",Map.of("casual",1,"persistent",1,"hardcore",1))),fake,fake.time).run(); fake.empty();
        assertThat(summary.outcomes().get("confirmed")).isEqualTo(1);
        assertThat(summary.requests().byEndpoint()).containsEntry("queue.leave",1L).containsEntry("queue.enter",2L);
        assertThat(summary.requests().sent()).isEqualTo(fake.calls.stream().filter(call -> !call.path().startsWith("/admin/")).count());
    }
    @Test void sameReturnBatchIsConsideredOnceEvenWhenReentryIsStillClosed() {
        var fake=new Fake();fake.setup().step(QUEUE,"POST","/queue/enter",200,Map.of("token",TOKEN,"status","CLOSED","reason","SOLD_OUT"));
        fake.step(SERVER,"GET","/admin/stats",200,Map.of("releaseBatches",Map.of("nextReleaseAt",fake.time.instant().toString()),
                "saleEndAt",fake.time.instant().plusSeconds(4).toString()))
                .step(QUEUE,"POST","/queue/enter",200,Map.of("token",TOKEN,"status","CLOSED","reason","SOLD_OUT"));
        var summary=new RunEngine(config(Map.of("timeScale",4,"revisitProb",Map.of("casual",1,"persistent",1,"hardcore",1))),fake,fake.time).run();fake.empty();
        assertThat(summary.requests().byEndpoint().get("queue.enter")).isEqualTo(2);
        assertThat(summary.events().get("requeues")).isEqualTo(1);
        assertThat(summary.outcomes().get("soldOut")).isEqualTo(1);
    }
    @Test void hardcoreRemainsAfterSoldOutAndRejoinsExpiredSession() {
        var fake=new Fake();fake.setup().admitted();
        for(int i=0;i<3;i++) fake.step(SERVER,"GET","/seats",200,dev.endnjs.simulator.SeatsContract.response(2,List.of()));
        fake.step(SERVER,"GET","/seats",403,Map.of("code","KEY_EXPIRED")).leave().admitted().available().held().checkout().auth().confirmed();
        var summary=new RunEngine(config(Map.of("churnMix",Map.of("casual",0,"persistent",0,"hardcore",100))),fake,fake.time).run();fake.empty();
        assertThat(summary.outcomes().get("confirmed")).isEqualTo(1);
        assertThat(summary.requests().byEndpoint()).containsEntry("queue.enter",2L).containsEntry("queue.leave",1L);
        assertThat(fake.adminReads).isEqualTo(1);
    }
    @Test void transportErrorAndMalformedSuccessBodyHaveDistinctResponseAccounting() {
        var fake = new Fake(); fake.setup().admitted().failure(SERVER,"GET","/seats");
        var summary = new RunEngine(config(Map.of()),fake,fake.time).run(); fake.empty();
        assertThat(summary.responses()).containsEntry("2xx",1L).containsEntry("transportErrors",1L);
        assertThat(summary.outcomes().get("error")).isEqualTo(1);
        assertThat(summary.avgLatencyMs().get("seats")).isZero();
        var malformed = new Fake(); malformed.setup().admitted().step(SERVER,"GET","/seats",200,Map.of());
        var invalid = new RunEngine(config(Map.of()),malformed,malformed.time).run(); malformed.empty();
        assertThat(invalid.responses()).containsEntry("2xx",2L).containsEntry("transportErrors",0L);
        assertThat(invalid.outcomes().get("error")).isEqualTo(1);
    }
    @Test void setupFailureStillProducesSummaryForEveryPersonaWithoutStartingUsers() {
        var fake = new Fake(); fake.step(QUEUE,"POST","/admin/reset",503,Map.of());
        var summary = new RunEngine(config(Map.of("users",3)),fake,fake.time).run(); fake.empty();
        assertThat(summary.requests().sent()).isEqualTo(0);
        assertThat(summary.responses().get("5xx")).isEqualTo(0);
        assertThat(summary.outcomes().get("error")).isEqualTo(3);
        assertThat(summary.outcomesByPersona().get("fast").get("error")).isEqualTo(3);
    }
    @Test void timeLimitIncludesUsersWhoHaveNotArrivedYetAndReturnsExitTwo() {
        var fake = new Fake(); fake.setup();
        var config = config(Map.of("timeLimitSec",1,"arrival",List.of(Map.of("percent",100,"fromSec",10,"toSec",10))));
        var summary = new RunEngine(config,fake,fake.time).run(); fake.empty();
        assertThat(summary.outcomes().get("incomplete")).isEqualTo(1);
        assertThat(summary.durationMs()).isBetween(1000L,1001L);
        assertThat(summary.requests().byEndpoint().get("queue.enter")).isZero();
        assertThat(summary.exitCode()).isEqualTo(2);
    }
    @Test void explicitStopInterruptsAllHttpUsersAndPreservesSummaryAndLiveTotals() throws Exception {
        int users = 20; var entered = new CountDownLatch(users);
        HttpTransport transport = request -> {
            if (request.path().startsWith("/admin/")) return new HttpTransport.Response(200,Map.of());
            entered.countDown(); new CountDownLatch(1).await(); throw new AssertionError("Stop should interrupt request");
        };
        var engine = new RunEngine(config(Map.of("users",users)),transport);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(engine::run);
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(engine.stats().sampleLive(true).users().get("waiting")).isEqualTo(users);
                engine.stop();
                var summary = result.get(5,TimeUnit.SECONDS);
                assertThat(summary.outcomes().get("incomplete")).isEqualTo(users);
                assertThat(summary.responses().get("transportErrors")).isEqualTo(users);
                assertThat(summary.requests().sent()).isEqualTo(users);
                assertThat(summary.outcomesByPersona().get("fast").get("incomplete")).isEqualTo(users);
                assertThat(engine.stats().sampleLive(false).users().get("done")).isEqualTo(users);
                assertThat(engine.running()).isFalse();
            } finally { engine.stop(); }
        }
    }
    private static final class FakeTime implements RunTime {
        final AtomicLong nanos = new AtomicLong();
        public Instant instant() { return Instant.parse("2026-10-02T09:00:00Z").plusNanos(nanos.get()); }
        public long nanoTime() { return nanos.get(); }
        public void sleep(long millis) { nanos.addAndGet(millis*1_000_000); }
    }
    private static final class Fake implements HttpTransport {
        record Step(Target target,String method,String path,Response response) {}
        int adminReads;
        Map<String,Object> adminSignal;
        final FakeTime time = new FakeTime(); final ArrayDeque<Step> steps = new ArrayDeque<>(); final List<Request> calls = new ArrayList<>();
        Fake step(Target target,String method,String path,int status,Map<String,Object> body) { steps.add(new Step(target,method,path,new Response(status,body))); return this; }
        Fake failure(Target target,String method,String path) { steps.add(new Step(target,method,path,null)); return this; }
        Fake setup() { return step(QUEUE,"POST","/admin/reset",200,Map.of()).step(PG,"POST","/admin/reset",200,Map.of()).step(SERVER,"POST","/admin/reset",200,Map.of()).step(PG,"PUT","/admin/config",200,Map.of()); } // RunEngine.prepare 순서
        Fake admitted() { return step(QUEUE,"POST","/queue/enter",200,Map.of("token",TOKEN,"status","ADMITTED","admissionKey",TOKEN)); }
        Fake available() { return step(SERVER,"GET","/seats",200,dev.endnjs.simulator.SeatsContract.response(2,List.of(dev.endnjs.simulator.SeatsContract.seat(1,"A1","AVAILABLE")))); }
        Fake held() { return step(SERVER,"POST","/holds",201,Map.of("reservationId",1)); }
        Fake checkout() { return step(SERVER,"POST","/holds/1/checkout",200,Map.of("orderId","order-1","amount",120000)); }
        Fake auth() { return step(PG,"POST","/auth",200,Map.of("paymentKey","key-1")); }
        Fake confirmed() { return step(SERVER,"POST","/payments/confirm",200,Map.of("status","CONFIRMED")); }
        Fake saleEnded() { return step(SERVER,"GET","/seats",403,Map.of("code","KEY_EXPIRED")).leave()
                .step(QUEUE,"POST","/queue/enter",200,Map.of("token",TOKEN,"status","CLOSED","reason","SALE_ENDED")); }
        Fake leave() { return step(QUEUE,"POST","/queue/leave",200,Map.of("status","LEFT")); }
        public Response exchange(Request request) throws IOException {
            if(request.path().equals("/admin/stats")) {
                adminReads++;
                if(steps.isEmpty() || !steps.peekFirst().path().equals("/admin/stats")) { calls.add(request); return new Response(200,adminSignal==null ? Map.of("saleEndAt",time.instant().toString()) : adminSignal); }
            }
            assertThat(steps).isNotEmpty(); var step = steps.removeFirst(); calls.add(request);
            assertThat(request.target()).isEqualTo(step.target()); assertThat(request.method()).isEqualTo(step.method()); assertThat(request.path()).isEqualTo(step.path());
            if (request.path().equals("/seats") || request.path().startsWith("/holds") || request.path().equals("/payments/confirm")) assertThat(request.headers()).containsKey("X-Admission-Key");
            if (request.path().equals("/holds")) assertThat(request.headers().get("Idempotency-Key")).isNotBlank();
            if(request.path().equals("/admin/stats") && step.response()!=null) adminSignal=step.response().body();
            time.nanos.addAndGet(4_000_000);
            if (step.response() == null) throw new IOException("network failure");
            return step.response();
        }
        void empty() { assertThat(steps).isEmpty(); }
    }
}
