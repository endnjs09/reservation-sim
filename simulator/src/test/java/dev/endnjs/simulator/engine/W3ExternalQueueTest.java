package dev.endnjs.simulator.engine;

import dev.endnjs.simulator.SeatsContract;
import dev.endnjs.simulator.http.JsonCodec;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class W3ExternalQueueTest {
    @Test void externalDefaultAndBusyCapFollowHoldAndRecoveryTimingUnlessExplicit() {
        var json=new JsonCodec();assertThat(json.config("{}").queueMode()).isEqualTo("EXTERNAL");
        assertThat(json.config("{\"holdTtlSec\":600,\"confirmDeadlineSec\":20}").busyMaxExtraSec()).isEqualTo(680);
        assertThat(json.config("{\"holdTtlSec\":600,\"busyMaxExtraSec\":123}").busyMaxExtraSec()).isEqualTo(123);
        assertThat(json.config("{\"queueMode\":\"EMBEDDED\"}").queueMode()).isEqualTo("EMBEDDED");
    }
    @ParameterizedTest @ValueSource(strings={"KEY_INVALID","KEY_EXPIRED","KEY_REVOKED"})
    void queueCallsUseQueueTargetAndEveryKeyFailureLeavesThenReentersAtTail(String code) {
        var config=new JsonCodec().config("{\"users\":1,\"timeScale\":1,\"arrival\":[{\"percent\":100,\"fromSec\":0,\"toSec\":0}]} ");
        var calls=new ArrayList<HttpTransport.Request>();var resets=new EnumMap<HttpTransport.Target,Map<String,Object>>(HttpTransport.Target.class);
        var now=Instant.parse("2026-10-04T00:00:00Z");var nanos=new AtomicLong();
        RunTime time=new RunTime() {
            public Instant instant() { return now.plusNanos(nanos.get()); }
            public long nanoTime() { return nanos.get(); }
            public void sleep(long millis) { nanos.addAndGet(millis*1_000_000L); }
        };
        var entered=new int[1];
        HttpTransport transport=request -> {
            calls.add(request);
            if(request.path().equals("/admin/reset")) { resets.put(request.target(),request.body());return new HttpTransport.Response(200,Map.of()); }
            if(request.path().startsWith("/admin/")) return new HttpTransport.Response(200,Map.of("saleEndAt",now.toString()));
            if(request.path().startsWith("/queue/")) {
                assertThat(request.target()).isEqualTo(HttpTransport.Target.QUEUE);
                if(request.path().equals("/queue/enter")) return new HttpTransport.Response(200,++entered[0]==1
                        ? Map.of("token","token","status","WAITING","pollAfterMs",1)
                        : Map.of("token","new-token","status","CLOSED","reason","SALE_ENDED"));
                if(request.path().startsWith("/queue/status")) return new HttpTransport.Response(200,Map.of("status","ADMITTED","admissionKey","issued-key","admissionExpiresAt",now.plusSeconds(420).toString()));
                return new HttpTransport.Response(200,Map.of());
            }
            assertThat(request.path()).isEqualTo("/seats");assertThat(request.target()).isEqualTo(HttpTransport.Target.SERVER);
            assertThat(request.headers()).containsEntry("X-Admission-Key","issued-key").doesNotContainKey("X-Queue-Token");return new HttpTransport.Response(403,Map.of("code",code));
        };
        var summary=new RunEngine(config,transport,time).run();
        assertThat(summary.outcomes()).containsEntry("soldOut",1L).containsEntry("error",0L);
        assertThat(summary.events()).containsEntry("requeues",1L);
        assertThat(summary.requests().byEndpoint()).containsEntry("queue.enter",2L).containsEntry("queue.status",1L).containsEntry("queue.leave",1L);
        assertThat(resets.get(HttpTransport.Target.QUEUE).get("runEpoch")).isEqualTo(resets.get(HttpTransport.Target.SERVER).get("runEpoch"));
        assertThat(resets.get(HttpTransport.Target.QUEUE).get("saleEndAt")).isEqualTo(resets.get(HttpTransport.Target.SERVER).get("saleEndAt"));
        assertThat(resets.get(HttpTransport.Target.SERVER)).doesNotContainKeys("queueEnabled","maxActive","admitPerSec","admissionTtlSec");
    }
    @Test void unavailableQueueFailsPreflightAndIdentifiesQueue() {
        var config=RunConfig.defaults();var targets=new ArrayList<HttpTransport.Target>();
        HttpTransport transport=request -> { targets.add(request.target());return new HttpTransport.Response(request.target()==HttpTransport.Target.QUEUE ? 503 : 200,Map.of("status","UP")); };
        assertThatThrownBy(()->RunPreflight.check(config,transport)).isInstanceOfSatisfying(RunPreflight.TargetUnavailable.class,failed -> assertThat(failed.target()).isEqualTo(HttpTransport.Target.QUEUE));
        assertThat(targets).containsExactly(HttpTransport.Target.SERVER,HttpTransport.Target.PG,HttpTransport.Target.QUEUE);
    }
    // 8.1-6: the server's SALE_ENDED / phase=ENDED is the primary signal; the shared saleEndAt only covers a missing answer.
    private static final Instant NOW=Instant.parse("2026-10-04T00:00:00Z");
    private static final Map<String,Object> SOLD_OUT_MAP=SeatsContract.with(
            SeatsContract.response(1,List.of(SeatsContract.seat(1,"A1","SOLD"))),"phase","SOLD_OUT");
    @FunctionalInterface private interface SeatsAnswer { HttpTransport.Response at(Instant now) throws java.io.IOException; }
    /** One hardcore user (never leaves on churn) who is admitted at once and then only browses /seats. */
    private static Summary browseUntilDone(int saleDurationSec,SeatsAnswer seats) {
        var config=new JsonCodec().config("{\"users\":1,\"timeScale\":1,\"saleDurationSec\":"+saleDurationSec
                +",\"arrival\":[{\"percent\":100,\"fromSec\":0,\"toSec\":0}],\"churnMix\":{\"casual\":0,\"persistent\":0,\"hardcore\":100}}");
        var nanos=new AtomicLong();
        RunTime time=new RunTime() {
            public Instant instant() { return NOW.plusNanos(nanos.get()); }public long nanoTime() { return nanos.get(); }
            public void sleep(long millis) { nanos.addAndGet(millis*1_000_000L); }
        };
        HttpTransport transport=request -> {
            if(request.path().startsWith("/admin/")) return new HttpTransport.Response(200,Map.of("saleEndAt",NOW.plusSeconds(saleDurationSec).toString()));
            if(request.path().equals("/queue/enter")) return new HttpTransport.Response(200,Map.of("token","token","status","WAITING","pollAfterMs",1));
            if(request.path().startsWith("/queue/status")) return new HttpTransport.Response(200,Map.of("status","ADMITTED","admissionKey","valid-key"));
            if(request.path().equals("/queue/leave")) return new HttpTransport.Response(200,Map.of());
            assertThat(request.path()).isEqualTo("/seats");
            return seats.at(time.instant());
        };
        return new RunEngine(config,transport,time).run();
    }
    private static void assertLeftAsSoldOut(Summary result,long fallbacks) {
        assertThat(result.outcomes()).containsEntry("soldOut",1L).containsEntry("error",0L).containsEntry("incomplete",0L);
        assertThat(result.requests().byEndpoint()).containsEntry("queue.leave",1L).containsEntry("holds",0L);
        assertThat(result.events()).containsEntry("saleEndFallback",fallbacks);
    }
    @Test void seatsSaleEndedErrorEndsBrowsingBeforeTheSharedSaleEnd() {
        var result=browseUntilDone(1200,now -> new HttpTransport.Response(409,Map.of("code","SALE_ENDED","message","Sale has ended")));
        assertLeftAsSoldOut(result,0);assertThat(result.requests().byEndpoint()).containsEntry("seats",1L);
    }
    @Test void seatsPhaseEndedEndsBrowsingBeforeTheSharedSaleEnd() {
        var result=browseUntilDone(1200,now -> new HttpTransport.Response(200,SeatsContract.with(SOLD_OUT_MAP,"phase","ENDED")));
        assertLeftAsSoldOut(result,0);assertThat(result.requests().byEndpoint()).containsEntry("seats",1L);
    }
    @Test void serverSaleEndedAfterRefreshingSoldOutMapIsNotAFallback() {
        var end=NOW.plusSeconds(30);
        var result=browseUntilDone(30,now -> now.isBefore(end) ? new HttpTransport.Response(200,SOLD_OUT_MAP)
                : new HttpTransport.Response(409,Map.of("code","SALE_ENDED","message","Sale has ended")));
        assertLeftAsSoldOut(result,0);
    }
    @ParameterizedTest @ValueSource(ints={503,0})
    void sharedSaleEndCoversMissingServerAnswerAfterTheEnd(int failure) {
        var end=NOW.plusSeconds(30);
        var result=browseUntilDone(30,now -> {
            if(now.isBefore(end)) return new HttpTransport.Response(200,SOLD_OUT_MAP);
            if(failure==0) throw new java.net.ConnectException("server down");
            return new HttpTransport.Response(failure,Map.of());
        });
        assertLeftAsSoldOut(result,1);
    }
    @Test void missingServerAnswerBeforeTheSaleEndIsStillAnError() {
        var result=browseUntilDone(1200,now -> { throw new java.net.ConnectException("server down"); });
        assertThat(result.outcomes()).containsEntry("error",1L).containsEntry("soldOut",0L);
        assertThat(result.events()).containsEntry("saleEndFallback",0L);
    }
    @Test void fakeSeatsBodiesHaveTheRealServerShape() {
        assertThat(SOLD_OUT_MAP.keySet()).containsExactlyInAnyOrderElementsOf(SeatsContract.fields());
        assertThat(SeatsContract.fields()).contains("availableSeats","heldSeats","pendingDepositSeats","returnPendingSeats","soldOut","releaseAt","saleEndAt","phase");
        @SuppressWarnings("unchecked") var seat=((List<Map<String,Object>>)SOLD_OUT_MAP.get("seats")).getFirst();
        assertThat(seat.keySet()).containsExactlyInAnyOrderElementsOf(SeatsContract.seatFields());
    }
}
