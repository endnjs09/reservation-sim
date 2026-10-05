package dev.endnjs.reservation;

import com.zaxxer.hikari.HikariDataSource;
import dev.endnjs.reservation.admin.AdminService;
import dev.endnjs.reservation.config.RuntimeConfigStore;
import dev.endnjs.reservation.expiry.ExpiryScheduler;
import dev.endnjs.reservation.hold.ConditionalHoldStrategy;
import dev.endnjs.reservation.metrics.MetricsService;
import dev.endnjs.reservation.metrics.MetricsSnapshot.Phase;
import dev.endnjs.reservation.payment.ConfirmRecoveryScheduler;
import dev.endnjs.reservation.payment.PgClient;
import dev.endnjs.reservation.payment.PgUnavailableException;
import dev.endnjs.reservation.sale.SaleEndScheduler;
import dev.endnjs.reservation.sale.SaleService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = "reservation.scheduler.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@Import(S1IntegrationTest.TestClockConfig.class)
class V1IntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("reservation").withUsername("reservation").withPassword("reservation");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("admission.required",()->false);r.add("internal.notifications-enabled",()->false);
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired RuntimeConfigStore configs;
    @Autowired AdminService admin;
    @Autowired SaleService sale;
    @Autowired SaleEndScheduler saleEnd;
    @Autowired ExpiryScheduler expiry;
    @Autowired ConfirmRecoveryScheduler recovery;
    @Autowired MetricsService metrics;
    @MockitoBean PgClient pg;
    @MockitoSpyBean HikariDataSource datasource;
    @MockitoSpyBean ConditionalHoldStrategy strategy;

    @BeforeEach void reset() throws Exception {
        clock.set(NOW);
        resetConfig(Map.of());
        when(pg.confirm(anyString(), any(UUID.class), anyInt())).thenReturn(new PgClient.Approval(true, null));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 4})
    void holdAndSaleDeadlinesUseSimulationTime(int scale) throws Exception {
        resetConfig(Map.of("timeScale", scale));
        UUID first=null;
        var held = hold("u-0", 1, first);
        assertThat(held.getResponse().getStatus()).isEqualTo(201);
        assertThat(Instant.parse(body(held).get("holdExpiresAt").asText()))
                .isEqualTo(NOW.plus(Duration.ofSeconds(420).dividedBy(scale)));
        assertThat(sale.timeline().saleEndAt()).isEqualTo(NOW.plus(Duration.ofSeconds(1200).dividedBy(scale)));

    }



    @Test void confirmRecoveryDeadlineIsScaledAndStillStartsWithStatusLookup() throws Exception {
        UUID owner=null;
        Order order = order("owner", 1, owner);
        unknownPg();
        assertThat(confirm(order, owner).getResponse().getStatus()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT confirm_deadline FROM reservations WHERE id=?", Timestamp.class, order.id()).toInstant())
                .isEqualTo(NOW.plusMillis(7500));
        clock.advance(Duration.ofMillis(7499));
        assertThat(recovery.runOnce()).isZero();
        clock.advance(Duration.ofMillis(1));
        doReturn(PgClient.Status.DONE).when(pg).status("key");
        clearInvocations(pg);
        assertThat(recovery.runOnce()).isEqualTo(1);
        verify(pg).status("key"); verify(pg, never()).cancel(anyString());
        assertThat(reservationStatus(order.id())).isEqualTo("CONFIRMED");
    }

    @Test void saleEndExpiresOnlyHeldThenRecoveryContinues() throws Exception {
        resetConfig(Map.of("saleDurationSec", 80));
        UUID first=null,second=null;
        Order confirming = order("first", 1, first);
        unknownPg();
        assertThat(confirm(confirming, first).getResponse().getStatus()).isEqualTo(202);
        long held = body(hold("second", 2, second)).get("reservationId").asLong();
        clock.advance(Duration.ofSeconds(20));
        assertThat(saleEnd.runOnce()).isEqualTo(1);
        assertThat(reservationStatus(held)).isEqualTo("EXPIRED");
        assertThat(reservationStatus(confirming.id())).isEqualTo("CONFIRMING");
        assertThat(count("seats", "AVAILABLE")).isEqualTo(3);
        Timestamp versionAt = jdbc.queryForObject("SELECT updated_at FROM seats WHERE id=2", Timestamp.class);
        assertThat(saleEnd.runOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM seats WHERE id=2", Timestamp.class)).isEqualTo(versionAt);
        doReturn(PgClient.Status.DONE).when(pg).status("key");
        assertThat(recovery.runOnce()).isEqualTo(1);
        assertThat(reservationStatus(confirming.id())).isEqualTo("CONFIRMED");
        assertThat(metrics.runOnce().phase()).isEqualTo(Phase.ENDED);
        clearInvocations(datasource);
    }

    @Test void newRequestsAreRejectedAtDeadlineBeforeSchedulerEvenWithoutAToken() throws Exception {
        resetConfig(Map.of("saleDurationSec", 80));
        clock.advance(Duration.ofSeconds(20));
        error(hold("late", 1, null), "SALE_ENDED");
        assertThat(count("reservations", "HELD")).isZero();
        resetConfig(Map.of("saleDurationSec", 80));
        clock.advance(Duration.ofSeconds(20));
        error(hold("disabled-queue", 1, null), "SALE_ENDED");
    }

    @Test void checkoutAndConfirmCannotPromoteHeldAfterSaleEnd() throws Exception {
        resetConfig(Map.of("saleDurationSec", 80));
        Order order = order("owner", 1, null);
        clock.advance(Duration.ofSeconds(20));
        error(postJson("/holds/" + order.id() + "/checkout", Map.of("userId", "owner"), null), "RESERVATION_NOT_PAYABLE");
        error(confirm(order, null), "RESERVATION_NOT_PAYABLE");
        verify(pg, never()).confirm(anyString(), any(UUID.class), anyInt());
        assertThat(saleEnd.runOnce()).isEqualTo(1);
    }

    @Test void approvalAlreadyInFlightCanFinishAfterSaleEnd() throws Exception {
        resetConfig(Map.of("saleDurationSec", 80));
        UUID token=null;
        var order = order("owner", 1, token);
        var inPg = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        when(pg.confirm(anyString(), any(UUID.class), anyInt())).thenAnswer(call -> {
            inPg.countDown();
            if (!proceed.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("PG test stalled");
            return new PgClient.Approval(true, null);
        });
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = workers.submit(() -> confirm(order, token));
            try {
                assertThat(inPg.await(5, TimeUnit.SECONDS)).isTrue();
                clock.advance(Duration.ofSeconds(20));
                assertThat(saleEnd.runOnce()).isZero();
                assertThat(reservationStatus(order.id())).isEqualTo("CONFIRMING");
            } finally { proceed.countDown(); }
            assertThat(response.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(reservationStatus(order.id())).isEqualTo("CONFIRMED");
        assertThat(count("seats", "SOLD")).isEqualTo(1);
    }

    @Test void heldRequestThatCrossesSaleEndRollsBackBeforeClosure() throws Exception {
        resetConfig(Map.of("saleDurationSec", 80));
        var inside = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        doAnswer(call -> {
            inside.countDown();
            if (!proceed.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Hold test stalled");
            return call.callRealMethod();
        }).when(strategy).acquire(eq(1L), any(Instant.class));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var holding = workers.submit(() -> hold("owner", 1, null));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            clock.advance(Duration.ofSeconds(20));
            var ending = workers.submit(saleEnd::runOnce);
            proceed.countDown();
            error(holding.get(5, TimeUnit.SECONDS), "SALE_ENDED");
            assertThat(ending.get(5, TimeUnit.SECONDS)).isZero();
        } finally { proceed.countDown(); }
        assertThat(count("reservations", "HELD")).isZero();
        assertThat(count("seats", "AVAILABLE")).isEqualTo(4);
    }

    @Test void startupRestoresTimelineWithoutResettingExistingReservations() throws Exception {
        clock.advance(Duration.ofNanos(123456789));
        resetConfig(Map.of("timeScale", 2, "saleDurationSec", 800));
        long held = body(hold("owner", 1, null)).get("reservationId").asLong();
        var original = sale.timeline();
        assertThat(original.startedAt()).isEqualTo(NOW.plusNanos(123456000));
        configs.replace(configs.current().withSaleTiming(4, 1200));
        clock.advance(Duration.ofSeconds(3));
        admin.run(null);
        assertThat(sale.timeline()).isEqualTo(original);
        assertThat(configs.current().timeScale()).isEqualTo(2);
        assertThat(configs.current().saleDurationSec()).isEqualTo(800);
        assertThat(reservationStatus(held)).isEqualTo("HELD");
        assertThat(metrics.snapshot().simElapsedSec()).isEqualTo(6);
    }

    @Test void resetReplacesSaleTimelineAndClearsPhaseHistory() throws Exception {
        hold("owner",1,null);
        clock.advance(Duration.ofSeconds(42));
        resetConfig(Map.of("timeScale", 2, "saleDurationSec", 90));
        var snapshot = metrics.snapshot();
        assertThat(snapshot.phase()).isEqualTo(Phase.OPEN);
        assertThat(snapshot.simElapsedSec()).isZero();
        assertThat(snapshot.timeScale()).isEqualTo(2);
        assertThat(snapshot.saleEndAt()).isEqualTo(clock.instant().plusSeconds(45));
        assertThat(snapshot.seatMap()).isEqualTo("AAAA");
        assertThat(metrics.runOnce().phase()).isEqualTo(Phase.OPEN);
    }

    @Test void invalidTimingDoesNotResetDataAndPartialResetRetainsCurrentTiming() throws Exception {
        resetConfig(Map.of()); hold("owner", 1, null);
        for (var invalid : List.of(Map.of("timeScale", 0), Map.of("timeScale", 3), Map.of("saleDurationSec", 0), Map.of("saleDurationSec", -1))) {
            assertThat(postJson("/admin/reset", invalid, null).getResponse().getStatus()).isEqualTo(400);
            assertThat(count("reservations", "HELD")).isEqualTo(1);
        }
        resetConfig(Map.of("timeScale", 2, "saleDurationSec", 90));
        var retained = body(postJson("/admin/reset", Map.of(), null)).get("config");
        assertThat(retained.get("timeScale").asInt()).isEqualTo(2);
        assertThat(retained.get("saleDurationSec").asInt()).isEqualTo(90);
    }

    @Test void phasePriorityAndRealTimeRequestRatesAreIndependentOfAcceleration() throws Exception {
        assertThat(Phase.resolve(true, false, false, false, 4, 0)).isEqualTo(Phase.ENDED);
        assertThat(Phase.resolve(false, false, true, true, 4, 0)).isEqualTo(Phase.OPEN);
        assertThat(Phase.resolve(false, true, false, true, 4, 0)).isEqualTo(Phase.RUSH);
        assertThat(Phase.resolve(false, true, true, true, 1, 0)).isEqualTo(Phase.REOPEN);
        assertThat(Phase.resolve(false, true, true, true, 0, 0)).isEqualTo(Phase.SOLD_OUT);
        assertThat(Phase.resolve(false, true, true, false, 0, 1)).isEqualTo(Phase.RESALE);
        assertThat(Phase.resolve(false, true, true, false, 1, 0)).isEqualTo(Phase.RESALE);
        UUID token=null;
        mvc.perform(get("/seats"));
        clock.advance(Duration.ofSeconds(1));
        var snapshot = metrics.runOnce();
        assertThat(snapshot.endpoints().get("seats").rps()).isEqualTo(1);
        assertThat(snapshot.simElapsedSec()).isEqualTo(4);
        assertThat(snapshot.timeScale()).isEqualTo(4);
        assertThat(snapshot.saleEndAt()).isEqualTo(NOW.plusSeconds(300));
    }

    private void unknownPg() {
        when(pg.confirm(anyString(), any(UUID.class), anyInt())).thenThrow(new PgUnavailableException("Unknown result"));
        when(pg.status(anyString())).thenThrow(new PgUnavailableException("Status unavailable"));
    }
    private void resetConfig(Map<String, Object> supplied) throws Exception {
        var config = new LinkedHashMap<String, Object>(Map.ofEntries(
                Map.entry("rows", 1), Map.entry("cols", 4),
                Map.entry("grades", List.of(Map.of("name", "VIP", "rows", 1, "price", 120000))),
                Map.entry("timeScale", 4), Map.entry("saleDurationSec", 1200), Map.entry("holdTtlSec", 420),
                Map.entry("confirmDeadlineSec", 30),
                Map.entry("closeQueueOnSoldOut", false),
                Map.entry("strategy", "conditional"), Map.entry("dbBackstop", true)));
        config.putAll(supplied);
        assertThat(postJson("/admin/reset", config, null).getResponse().getStatus()).isEqualTo(200);
    }
    private MvcResult hold(String user, long seat, UUID token) throws Exception {
        return postJson("/holds", Map.of("userId", user, "seatId", seat), token, UUID.randomUUID().toString());
    }
    private Order order(String user, long seat, UUID token) throws Exception {
        long id = body(hold(user, seat, token)).get("reservationId").asLong();
        var response = body(postJson("/holds/" + id + "/checkout", Map.of("userId", user), token));
        return new Order(id, user, UUID.fromString(response.get("orderId").asText()), response.get("amount").asInt());
    }
    private MvcResult confirm(Order order, UUID token) throws Exception {
        return postJson("/payments/confirm", Map.of("userId", order.user(), "orderId", order.orderId(), "amount", order.amount(), "paymentKey", "key"), token);
    }
    private MvcResult postJson(String path, Object value, UUID token) throws Exception { return postJson(path, value, token, null); }
    private MvcResult postJson(String path, Object value, UUID token, String key) throws Exception {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(value));
        if (token != null) request.header("X-Admission-Key", token);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request).andReturn();
    }
    private JsonNode body(MvcResult response) throws Exception { return json.readTree(response.getResponse().getContentAsString()); }
    private void error(MvcResult response, String code) throws Exception {
        assertThat(response.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(response).get("code").asText()).isEqualTo(code);
    }
    private long count(String table, String status) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE status=?", Long.class, status); }
    private String reservationStatus(long id) { return jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, id); }
    private record Order(long id, String user, UUID orderId, int amount) {}
}
