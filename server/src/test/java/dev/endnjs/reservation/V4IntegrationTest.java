package dev.endnjs.reservation;

import com.zaxxer.hikari.HikariDataSource;
import dev.endnjs.reservation.admin.AdminService;
import dev.endnjs.reservation.seat.SeatRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import dev.endnjs.reservation.deposit.DepositExpiryScheduler;
import dev.endnjs.reservation.expiry.ExpiryScheduler;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.metrics.MetricsService;
import dev.endnjs.reservation.payment.PgClient;
import dev.endnjs.reservation.payment.PgUnavailableException;
import dev.endnjs.reservation.resale.ReopenScheduler;
import dev.endnjs.reservation.sale.SaleEndScheduler;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties="reservation.scheduler.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@Import(S1IntegrationTest.TestClockConfig.class)
class V4IntegrationTest {
    private static final Instant NOW=Instant.parse("2026-10-03T05:00:00Z");
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17")
            .withDatabaseName("reservation").withUsername("reservation").withPassword("reservation");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("admission.required",()->false);r.add("internal.notifications-enabled",()->false);
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired DepositExpiryScheduler depositExpiry;
    @Autowired ReopenScheduler reopen;
    @Autowired SaleEndScheduler saleEnd;
    @Autowired MetricsService metrics;
    @Autowired AdminService admin;
    @MockitoBean PgClient pg;
    @MockitoSpyBean HikariDataSource datasource;
    @MockitoSpyBean SeatRepository seatRepository;
    @MockitoSpyBean dev.endnjs.reservation.snapshot.StateSnapshotReader snapshots;
    @MockitoSpyBean dev.endnjs.reservation.metrics.MetricsRepository metricsRepository;

    @BeforeEach void reset() throws Exception { clock.set(NOW); configure(Map.of()); }

    @Test void v05SharedSaleDeadlineAndSnapshotPreserveActualRowsAndErrorCodes() throws Exception {
        configure(Map.of("runEpoch","shared-epoch","saleEndAt",NOW.plusSeconds(300).toString()));
        assertThat(stats().get("runEpoch").asText()).isEqualTo("shared-epoch");
        assertThat(stats().get("saleEndAt").asText()).isEqualTo(NOW.plusSeconds(300).toString());
        String token=null;long id=hold("owner",List.of(1,2),token);
        String other=null;
        var conflict=postJson("/holds",Map.of("userId","other","seatIds",List.of(1,2)),other);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        var sample=metrics.runOnce();
        assertThat(sample.endpoints().get("holds").errors()).containsEntry("SEAT_UNAVAILABLE",1L);
        assertThat(sample.total().errorClasses()).containsEntry("conflict",1L);
        assertThat(sample.endpoints().get("holds").latency().p95()).isNotNull();
        var snapshot=body(mvc.perform(get("/admin/snapshot")).andReturn());
        assertThat(snapshot.get("seats").size()).isEqualTo(2);assertThat(snapshot.get("reservations").size()).isEqualTo(1);
        assertThat(snapshot.get("reservation_seats").size()).isEqualTo(2);assertThat(snapshot.has("payments")).isTrue();assertThat(snapshot.has("release_batches")).isTrue();
        assertThat(snapshot.get("reservations").get(0).get("id").asLong()).isEqualTo(id);
        assertThat(snapshot.get("counters").get("holdSuccess").asLong()).isEqualTo(1);
    }















    @Test void phaseUsesPersistentZeroHistoryAndFollowsAllSixPhases() throws Exception {
        configure(Map.of());
        assertPhase("OPEN"); long a=hold("a",List.of(1),null); assertPhase("RUSH");
        long b=hold("b",List.of(2),null); assertPhase("RESALE");
        assertThat(jdbc.queryForObject("SELECT ever_zero FROM sale_lifecycle",Boolean.class)).isTrue();
        deposit("a",a,null); deposit("b",b,null); assertPhase("SOLD_OUT");
        clock.advance(Duration.ofSeconds(60)); depositExpiry.runOnce(); assertPhase("SOLD_OUT");
        clock.advance(Duration.ofSeconds(30)); reopen.runOnce(); assertPhase("REOPEN");
        clock.advance(Duration.ofSeconds(50)); assertPhase("RESALE");
        admin.run(null); assertPhase("RESALE"); // Startup rebuild cannot erase phase history.
        clock.set(NOW.plusSeconds(1200)); saleEnd.runOnce(); assertPhase("ENDED");
        clock.set(NOW.plusSeconds(1300)); configure(Map.of()); assertPhase("OPEN");
        assertThat(jdbc.queryForObject("SELECT ever_zero FROM sale_lifecycle",Boolean.class)).isFalse();
    }







    @Test void admittedSeatListAndSoldOutUseOneRepeatableSnapshot() throws Exception {
        configure(Map.of());
        var entered=new CountDownLatch(1); var proceed=new CountDownLatch(1);
        doAnswer(inv -> { var rows=inv.callRealMethod(); entered.countDown(); assertThat(proceed.await(10,TimeUnit.SECONDS)).isTrue(); return rows; }).when(seatRepository).findAll();
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var reading=workers.submit(() -> mvc.perform(get("/seats")).andReturn());
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); jdbc.update("UPDATE seats SET status='SOLD'");
                proceed.countDown(); JsonNode result=body(reading.get(10,TimeUnit.SECONDS));
                assertThat(result.get("soldOut").asBoolean()).isFalse();
                for(JsonNode seat:result.get("seats")) assertThat(seat.get("status").asText()).isEqualTo("AVAILABLE");
            } finally { proceed.countDown(); doCallRealMethod().when(seatRepository).findAll(); }
        }
        assertThat(body(mvc.perform(get("/seats")).andReturn()).get("soldOut").asBoolean()).isTrue();
    }

    @Test void metricsRetainsInventoryAndSaleTimelineFromSameRunAcrossConcurrentReset() throws Exception {
        configure(Map.of());
        long id=hold("owner",List.of(1,2),null);
        var entered=new CountDownLatch(1); var proceed=new CountDownLatch(1);
        var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(inv -> {
            var snapshot=inv.callRealMethod();
            if(first.compareAndSet(true,false)) { entered.countDown(); assertThat(proceed.await(10,TimeUnit.SECONDS)).isTrue(); }
            return snapshot;
        }).when(snapshots).metrics(any());
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var reading=workers.submit(metrics::runOnce);
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                clock.set(NOW.plusSeconds(100));
                var resetting=workers.submit(() -> { configure(Map.of("timeScale",4,"saleDurationSec",900)); return true; });
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!jdbc.queryForObject("SELECT time_scale=4 FROM sale_lifecycle",Boolean.class) && System.nanoTime()<until) Thread.sleep(10);
                assertThat(jdbc.queryForObject("SELECT time_scale=4 FROM sale_lifecycle",Boolean.class)).isTrue();
                proceed.countDown();var old=reading.get(10,TimeUnit.SECONDS);
                assertThat(old.heldSeats()).isEqualTo(2);assertThat(old.timeScale()).isEqualTo(1);
                assertThat(old.saleEndAt()).isEqualTo(NOW.plusSeconds(1200));assertThat(old.simElapsedSec()).isZero();
                assertThat(resetting.get(10,TimeUnit.SECONDS)).isTrue();
                var current=metrics.snapshot();assertThat(current.availableSeats()).isEqualTo(2);assertThat(current.timeScale()).isEqualTo(4);
                assertThat(current.saleEndAt()).isEqualTo(NOW.plusSeconds(325));
            } finally { proceed.countDown();doCallRealMethod().when(snapshots).metrics(any()); }
        }
    }

    @ParameterizedTest @ValueSource(strings={"seats","metrics"})
    void resetWaitsForWholeSnapshotWithoutTableLockDeadlock(String view) throws Exception {
        configure(Map.of());
        var entered=new CountDownLatch(1); var proceed=new CountDownLatch(1);
        var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.stubbing.Answer<Object> pause=inv -> {
            var result=inv.callRealMethod();
            if(first.compareAndSet(true,false)) {
                entered.countDown(); assertThat(proceed.await(10,TimeUnit.SECONDS)).isTrue();
            }
            return result;
        };
        if(view.equals("seats")) doAnswer(pause).when(seatRepository).findAll();
        else doAnswer(pause).when(metricsRepository).seats(any());
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var reading=workers.submit(() -> view.equals("seats")
                    ? body(mvc.perform(get("/seats")).andReturn()) : json.valueToTree(metrics.runOnce()));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                var resetting=workers.submit(() -> { configure(Map.of("cols",3)); return true; });
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                String waiting="SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' "
                        +"AND (query LIKE 'LOCK TABLE %' OR query LIKE 'TRUNCATE %')";
                while(jdbc.queryForObject(waiting,Long.class)==0 && System.nanoTime()<until) Thread.sleep(10);
                assertThat(jdbc.queryForObject(waiting,Long.class)).isPositive();
                proceed.countDown(); JsonNode old=reading.get(10,TimeUnit.SECONDS);
                if(view.equals("seats")) {
                    assertThat(old.get("seats").size()).isEqualTo(2);
                    for(JsonNode seat:old.get("seats")) assertThat(seat.get("status").asText()).isEqualTo("AVAILABLE");
                } else assertThat(old.get("availableSeats").asInt()).isEqualTo(2);
                assertThat(resetting.get(10,TimeUnit.SECONDS)).isTrue();
                assertThat(stats().get("seats").get("AVAILABLE").asInt()).isEqualTo(3);
            } finally {
                proceed.countDown(); doCallRealMethod().when(seatRepository).findAll();
                doCallRealMethod().when(metricsRepository).seats(any());
            }
        }
    }

    private void configure(Map<String,Object> changes) throws Exception {
        var config=new java.util.LinkedHashMap<String,Object>(Map.ofEntries(
                Map.entry("rows",1),Map.entry("cols",2),Map.entry("grades",List.of(Map.of("name","VIP","rows",1,"price",100))),
                Map.entry("closeQueueOnSoldOut",false),Map.entry("holdTtlSec",420),Map.entry("confirmDeadlineSec",30),
                Map.entry("strategy","conditional"),Map.entry("dbBackstop",true),Map.entry("timeScale",1),Map.entry("saleDurationSec",1200),
                Map.entry("maxSeatsPerUser",4),Map.entry("depositDeadlineSec",60),Map.entry("returnDelaySec",30),Map.entry("reopenWindowSec",50)));
        config.putAll(changes); assertThat(postJson("/admin/reset",config,null).getResponse().getStatus()).isEqualTo(200);
    }
    private long hold(String user,List<Integer> ids,String token) throws Exception {
        var result=postJson("/holds",Map.of("userId",user,"seatIds",ids),token);
        assertThat(result.getResponse().getStatus()).isEqualTo(201); return body(result).get("reservationId").asLong();
    }
    private void deposit(String user,long id,String token) throws Exception { assertThat(postJson("/holds/"+id+"/deposit",Map.of("userId",user),token).getResponse().getStatus()).isEqualTo(200); }
    private JsonNode stats() throws Exception { return body(mvc.perform(get("/admin/stats")).andReturn()); }
    private void assertPhase(String phase) throws Exception {
        var snapshot=metrics.runOnce(); assertThat(snapshot.phase().name()).isEqualTo(phase);
        assertThat(stats().get("phase").asText()).isEqualTo(phase);
        assertThat(snapshot.availableSeats()+snapshot.heldSeats()+snapshot.pendingDepositSeats()+snapshot.returnPendingSeats()+snapshot.soldSeats()).isEqualTo(2);
    }
    private MvcResult postJson(String path,Object data,String token) throws Exception {
        var request=post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data));
        if(token!=null) request.header("X-Admission-Key",token); if(path.equals("/holds")) request.header("Idempotency-Key",UUID.randomUUID().toString());
        return mvc.perform(request).andReturn();
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
}
