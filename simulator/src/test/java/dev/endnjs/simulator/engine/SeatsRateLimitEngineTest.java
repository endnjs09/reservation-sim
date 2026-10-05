package dev.endnjs.simulator.engine;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import dev.endnjs.simulator.SeatsContract;
import dev.endnjs.simulator.http.JsonCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 사용자: GET /seats가 429 RATE_LIMITED면 retryAfterMs만큼 기다렸다가 다시 조회. 에러·실패로 세지 않는다. */
class SeatsRateLimitEngineTest {
    @Test void userWaitsRetryAfterThenRefreshesAndItIsNotAFailure() {
        var config=new JsonCodec().config("""
                {"rows":1,"cols":4,"grades":[{"name":"VIP","rows":1,"price":100}],"users":1,"arrival":[{"percent":100,"fromSec":0,"toSec":0}],
                 "personaMix":{"fast":100,"normal":0,"slow":0},
                 "personas":{"fast":{"selectSec":{"min":0,"max":0},"authSec":{"min":0,"max":0},"refreshSec":1}},
                 "priceStepSec":{"fast":{"min":0,"max":0}},"ticketCountMix":{"1":0,"2":100,"3":0,"4":0},"paymentMix":{"card":100,"deposit":0},
                 "abandonRate":0,"cancelAfterPurchaseRate":0,"timeScale":1,"timeLimitSec":100,"saleDurationSec":20}""");
        var fake=new V5EngineTest.Fake(config);
        fake.setup().admitted().step("GET","/seats",429,SeatsContract.rateLimited(700)).available().held().checkout().auth().confirmed();
        var summary=new RunEngine(config,fake,fake.time).run();fake.empty();
        assertThat(summary.outcomes()).containsEntry("confirmed",1L).containsEntry("error",0L);
        var lookups=fake.calls.stream().filter(c -> c.request().path().equals("/seats")).toList();
        assertThat(lookups).hasSize(2);
        assertThat(Duration.between(lookups.get(0).at(),lookups.get(1).at())).isGreaterThanOrEqualTo(Duration.ofMillis(700));
        assertThat(summary.events()).containsEntry("rateLimited",1L);
        assertThat(summary.errorClasses()).containsEntry("rateLimited",1L).containsEntry("shed",0L).containsEntry("client",0L);
    }
    @Test void rateLimitedRequestsLeaveTheFailureRateDenominator() {
        var config=new JsonCodec().config("{\"users\":1,\"timeScale\":1}");
        var stats=new RunStats(config,RunTime.system(),List.of(Persona.FAST));
        stats.request("seats",HttpTransport.Target.SERVER).response(200);
        stats.request("seats",HttpTransport.Target.SERVER).response(429,"RATE_LIMITED");
        stats.request("holds",HttpTransport.Target.SERVER).response(500);
        stats.summary();
        // failPct = 실패 ÷ 보낸 요청, 429는 양쪽 모두에서 빠짐: 1 ÷ 2
        assertThat(stats.clientMetrics().serverFailed()).isEqualTo(1);
        assertThat(stats.clientMetrics().serverSent()).isEqualTo(2);
    }
    @Test void newRunDefaultsLimitRefreshesAndCloseTheQueueOnSoldOut() {
        assertThat(RunConfig.defaults().seatsRateLimitEnabled()).isTrue();
        assertThat(RunConfig.defaults().seatsMinIntervalSec()).isEqualTo(1.0);
        assertThat(RunConfig.defaults().seatsCacheSec()).isZero();
        assertThat(RunConfig.defaults().closeQueueOnSoldOut()).isTrue();
        var config=new JsonCodec().config("{}");
        assertThat(config.seatsRateLimitEnabled()).isTrue();assertThat(config.closeQueueOnSoldOut()).isTrue(); // API·프리셋은 기본값과 합침
    }
    @Test void resetSendsTheSeatsSettingsToTheServer() {
        var config=new JsonCodec().config("{\"users\":1,\"timeScale\":2,\"seatsCacheSec\":1,\"seatsMinIntervalSec\":1.5,\"seatsRateLimitEnabled\":false}");
        var fake=new V5EngineTest.Fake(config);
        fake.setup().step("POST","/queue/enter",409,Map.of("code","SALE_ENDED"));
        new RunEngine(config,fake,fake.time).run();
        var reset=fake.calls.stream().filter(c -> c.request().target()==HttpTransport.Target.SERVER && c.request().path().equals("/admin/reset")).findFirst().orElseThrow();
        assertThat(reset.request().body()).containsEntry("seatsCacheSec",1).containsEntry("seatsMinIntervalSec",1.5).containsEntry("seatsRateLimitEnabled",false);
    }
}
