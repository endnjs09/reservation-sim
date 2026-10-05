package dev.endnjs.reservation;

import com.sun.net.httpserver.HttpServer;
import dev.endnjs.reservation.admission.*;
import dev.endnjs.reservation.payment.PgClient;
import dev.endnjs.reservation.expiry.ExpiryScheduler;
import dev.endnjs.reservation.sale.SaleEndScheduler;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"reservation.scheduler.enabled=false","admission.required=true","admission.key-secret=integration-secret"})
@AutoConfigureMockMvc
@Testcontainers
@Import(S1IntegrationTest.TestClockConfig.class)
class W3AdmissionIntegrationTest {
    private static final Instant NOW=Instant.parse("2026-10-04T00:00:00Z");
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17")
            .withDatabaseName("reservation").withUsername("reservation").withPassword("reservation");
    private static final List<Map<String,Object>> EVENTS=new CopyOnWriteArrayList<>();
    private static final HttpServer QUEUE=queue();
    private static HttpServer queue() {
        try {
            var queue=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            queue.createContext("/",request -> {
                var body=JsonMapper.builder().build().readValue(request.getRequestBody(),Map.class);
                var event=new LinkedHashMap<String,Object>(body);event.put("path",request.getRequestURI().getPath());EVENTS.add(event);
                request.sendResponseHeaders(200,-1);request.close();
            });queue.start();return queue;
        } catch(java.io.IOException failed) { throw new IllegalStateException(failed); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);registry.add("spring.datasource.username",POSTGRES::getUsername);registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("admission.queue-url",()->"http://127.0.0.1:"+QUEUE.getAddress().getPort());
    }
    @AfterAll static void closeQueue() { QUEUE.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired KeyRegistry revoked;
    @Autowired SlotNotifier notifier;
    @Autowired dev.endnjs.reservation.hold.ReservationRepository reservations;
    @Autowired dev.endnjs.reservation.seat.SeatRepository seats;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired ExpiryScheduler expiry;
    @Autowired SaleEndScheduler saleEnd;
    @MockitoBean PgClient pg;
    @MockitoSpyBean AdmissionKeyChecker checker;
    private UUID kid;
    private String key;
    @BeforeEach void reset() throws Exception {
        clock.set(NOW);EVENTS.clear();
        var config=Map.ofEntries(Map.entry("rows",1),Map.entry("cols",4),Map.entry("grades",List.of(Map.of("name","VIP","rows",1,"price",100))),
                Map.entry("strategy","conditional"),Map.entry("dbBackstop",true),Map.entry("timeScale",1),Map.entry("saleDurationSec",1200),
                Map.entry("holdTtlSec",420),Map.entry("runEpoch","current"));
        assertThat(postJson("/admin/reset",config,null).getResponse().getStatus()).isEqualTo(200);
        kid=UUID.randomUUID();key=sign(kid,"owner","current",NOW.plusSeconds(1));
        when(pg.confirm(anyString(),any(UUID.class),anyInt())).thenReturn(new PgClient.Approval(true,null));
    }
    private String sign(UUID kid,String uid,String run,Instant exp) throws Exception {
        String message="v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(Map.of("kid",kid.toString(),"uid",uid,"run",run,"iat",NOW.toEpochMilli(),"exp",exp.toEpochMilli())));
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("integration-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return message+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
    private MvcResult postJson(String path,Object body,String key) throws Exception {
        var request=post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if(key!=null) request.header("X-Admission-Key",key);
        if(path.equals("/holds")) request.header("Idempotency-Key",UUID.randomUUID().toString());
        return mvc.perform(request).andReturn();
    }
    private JsonNode body(MvcResult result) { return json.readTree(result.getResponse().getContentAsByteArray()); }
    private long hold() throws Exception {
        var held=postJson("/holds",Map.of("userId","owner","seatIds",List.of(1)),key);
        assertThat(held.getResponse().getStatus()).isEqualTo(201);return body(held).get("reservationId").asLong();
    }
    private void denied(MvcResult result,String code) {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);assertThat(body(result).get("code").asString()).isEqualTo(code);
    }
    private void awaitEvent(String type) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(EVENTS.stream().noneMatch(e->type.equals(e.get("type")) && ("/internal/slots/"+kid+"/events").equals(e.get("path"))) && System.nanoTime()<until) Thread.sleep(10);
        assertThat(EVENTS).anySatisfy(e->assertThat(e).containsEntry("type",type).containsEntry("path","/internal/slots/"+kid+"/events"));
    }
    @Test void migrationAndPublicMetricsHaveNoEmbeddedQueueAndSnapshotIncludesDroppedEvidence() throws Exception {
        assertThat(jdbc.queryForObject("SELECT to_regclass('queue_tokens') IS NULL",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='reservations' AND column_name='admission_kid'",Long.class)).isEqualTo(1);
        var stats=body(mvc.perform(get("/admin/stats")).andReturn());assertThat(stats.has("queue")).isFalse();
        var metrics=body(mvc.perform(get("/admin/metrics")).andReturn());assertThat(metrics.has("queue")).isFalse();
        assertThat(metrics.has("idleInside")).isTrue();assertThat(metrics.get("idleInside").isNull()).isTrue();
        assertThat(metrics.get("schedulers").properties().stream().map(Map.Entry::getKey)).containsExactlyInAnyOrder("expiry","recovery","depositExpiry","reopen");
        var snapshot=body(mvc.perform(get("/admin/snapshot")).andReturn());assertThat(snapshot.get("droppedNotifications").isArray()).isTrue();assertThat(snapshot.get("counters").get("acceptedInvalidKeys").asLong()).isZero();
        assertThat(mvc.perform(get("/queue/status").param("token",UUID.randomUUID().toString())).andReturn().getResponse().getStatus()).isEqualTo(404);
    }
    @Test void signatureRunUserRevocationAndExpiryReturnDistinct403Codes() throws Exception {
        denied(mvc.perform(get("/seats")).andReturn(),"KEY_INVALID");
        denied(mvc.perform(get("/seats").header("X-Admission-Key",sign(kid,"owner","old",NOW.plusSeconds(10)))).andReturn(),"KEY_INVALID");
        denied(postJson("/holds",Map.of("userId","other","seatIds",List.of(1)),key),"KEY_INVALID");
        revoked.revoke(kid);denied(mvc.perform(get("/seats").header("X-Admission-Key",key)).andReturn(),"KEY_REVOKED");
        revoked.reset();clock.set(NOW.plusSeconds(1));denied(mvc.perform(get("/seats").header("X-Admission-Key",key)).andReturn(),"KEY_EXPIRED");
    }
    @ParameterizedTest @ValueSource(strings={"checkout","deposit","release","confirm"})
    void expiredKeyProtectsOnlyExistingOngoingReservation(String action) throws Exception {
        long id=hold();var order=body(postJson("/holds/"+id+"/checkout",Map.of("userId","owner"),key));clock.set(NOW.plusSeconds(2));
        denied(mvc.perform(get("/seats").header("X-Admission-Key",key)).andReturn(),"KEY_EXPIRED");
        denied(postJson("/holds",Map.of("userId","owner","seatIds",List.of(2)),key),"KEY_EXPIRED");
        denied(postJson("/holds/999/checkout",Map.of("userId","owner"),key),"KEY_EXPIRED");
        MvcResult response=action.equals("confirm") ? postJson("/payments/confirm",Map.of("userId","owner","orderId",order.get("orderId").asString(),"paymentKey","key","amount",100),key)
                : postJson("/holds/"+id+"/"+action,Map.of("userId","owner"),key);
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(mvc.perform(get("/admin/stats")).andReturn()).get("counters").get("acceptedInvalidKeys").asLong()).isZero();
    }
    @Test void depositCompletesAndRevokesKidWhilePaymentAndCancellationNeedNoKey() throws Exception {
        long id=hold();assertThat(jdbc.queryForObject("SELECT admission_kid FROM reservations WHERE id=?",UUID.class,id)).isEqualTo(kid);awaitEvent("HOLD_ACTIVE");
        assertThat(postJson("/holds/"+id+"/deposit",Map.of("userId","owner"),key).getResponse().getStatus()).isEqualTo(200);awaitEvent("COMPLETED");
        denied(mvc.perform(get("/seats").header("X-Admission-Key",key)).andReturn(),"KEY_REVOKED");
        assertThat(postJson("/deposits/"+id+"/pay",Map.of("userId","owner","amount",100),null).getResponse().getStatus()).isEqualTo(200);
        assertThat(postJson("/reservations/"+id+"/cancel",Map.of("userId","owner"),null).getResponse().getStatus()).isEqualTo(200);
    }
    @ParameterizedTest @ValueSource(strings={"release","expiry","saleEnd","decline"})
    void everyHoldTerminationNotifiesItsStoredKid(String action) throws Exception {
        long id=hold();awaitEvent("HOLD_ACTIVE");
        switch(action) {
            case "release" -> postJson("/holds/"+id+"/release",Map.of("userId","owner"),key);
            case "expiry" -> { clock.set(NOW.plusSeconds(420));assertThat(expiry.runOnce()).isEqualTo(1); }
            case "saleEnd" -> { clock.set(NOW.plusSeconds(1200));assertThat(saleEnd.runOnce()).isEqualTo(1); }
            case "decline" -> {
                var order=body(postJson("/holds/"+id+"/checkout",Map.of("userId","owner"),key));when(pg.confirm(anyString(),any(UUID.class),anyInt())).thenReturn(new PgClient.Approval(false,"DECLINED"));
                assertThat(postJson("/payments/confirm",Map.of("userId","owner","orderId",order.get("orderId").asString(),"paymentKey","key","amount",100),key).getResponse().getStatus()).isEqualTo(402);
            }
        }
        awaitEvent("HOLD_CLEARED");assertThat(revoked.revoked(kid)).isFalse();
    }
    @Test void independentAuditorDetectsMainValidatorBypassOnActualCommittedHold() throws Exception {
        doReturn(new AdmissionKeyChecker.Claims(kid,"owner",NOW.plusSeconds(20).toEpochMilli())).when(checker).check(eq("broken-key"),any());
        var response=postJson("/holds",Map.of("userId","owner","seatIds",List.of(1)),"broken-key");assertThat(response.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(mvc.perform(get("/admin/stats")).andReturn()).get("counters").get("acceptedInvalidKeys").asLong()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"release","deposit"})
    void naiveMultipleHoldsClearOlderKeysOnlyWhenLastOngoingReservationEnds(String lastAction) throws Exception {
        assertThat(postJson("/admin/reset",Map.of("strategy","naive","dbBackstop",false,"runEpoch","current"),null).getResponse().getStatus()).isEqualTo(200);
        long first=hold();awaitEvent("HOLD_ACTIVE");UUID otherKid=UUID.randomUUID();String otherKey=sign(otherKid,"owner","current",NOW.plusSeconds(1));
        // Sequential requests correctly reject a second hold. Reproduce the committed
        // state of two unsafe concurrent requests without changing that public policy.
        long last=new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
            seats.unconditionalHold(2,NOW);
            var second=reservations.create("owner",List.of(2L),UUID.randomUUID().toString(),NOW,NOW.plusSeconds(420),otherKid);
            seats.attachReservation(List.of(2L),second.id());return second.id();
        });
        postJson("/holds/"+first+"/release",Map.of("userId","owner"),key);Thread.sleep(100);
        assertThat(EVENTS).noneSatisfy(event->assertThat(event.get("type")).isEqualTo("HOLD_CLEARED"));
        postJson("/holds/"+last+"/"+lastAction,Map.of("userId","owner"),otherKey);awaitEvent("HOLD_CLEARED");
        if(lastAction.equals("deposit")) assertThat(revoked.revoked(otherKid)).isTrue();
    }
}
