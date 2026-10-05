package dev.endnjs.reservation;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import dev.endnjs.reservation.expiry.ExpiryScheduler;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.metrics.MetricsRepository;
import dev.endnjs.reservation.metrics.MetricsService;
import dev.endnjs.reservation.payment.ConfirmRecoveryScheduler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {"reservation.scheduler.enabled=false", "reservation.time-scale=1"})
@AutoConfigureMockMvc
@Testcontainers
@Import(S1IntegrationTest.TestClockConfig.class)
class S4IntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");
    private static final PgStub PG = new PgStub();
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("reservation").withUsername("reservation").withPassword("reservation");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("admission.required",()->false);registry.add("internal.notifications-enabled",()->false);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("admission.required", () -> false);
        registry.add("reservation.pg.base-url", PG::baseUrl);
        registry.add("reservation.pg.request-timeout-ms", () -> 10000);
    }
    @AfterAll static void stopPg() { PG.close(); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired MetricsService snapshots;
    @Autowired MetricsCollector metrics;
    @Autowired ExpiryScheduler expiry;
    @Autowired ConfirmRecoveryScheduler recovery;
    @MockitoSpyBean HikariDataSource datasource;
    @MockitoSpyBean MetricsRepository repository;

    @BeforeEach
    void reset() throws Exception {
        clock.set(NOW);
        PG.reset();
        resetConfig(false);
    }

    @Test
    void completedRequestsAreGroupedByEndpointAndStatusAndWindowsAreDrainedOnce() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(mvc.perform(get("/seats")).andReturn().getResponse().getStatus()).isEqualTo(200);
        long held = hold("owner", 1, null);
        assertThat(holdResponse("other", 1, null).getResponse().getStatus()).isEqualTo(409);
        assertThat(postJson("/holds", Map.of("userId", "invalid", "seatId", 2), null).getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/holds/" + held + "/checkout", Map.of("userId", "owner"), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(postJson("/holds/" + held + "/release", Map.of("userId", "owner"), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/reservations/" + held)).andReturn().getResponse().getStatus()).isEqualTo(200);
        var snapshot = sample();
        assertEndpoint(snapshot, "seats", 3, Map.of("2xx", 3));
        assertEndpoint(snapshot, "holds", 3, Map.of("2xx", 1, "409", 1, "400", 1));
        for (String endpoint : List.of("checkout", "release", "reservation")) assertEndpoint(snapshot, endpoint, 1, Map.of("2xx", 1));
        assertThat(snapshot.get("inflightTotal").asInt()).isZero();
        assertThat(snapshot.get("endpoints").has("admin.metrics")).isFalse();
        assertThat(snapshot.get("endpoints").get("holds").get("avgMs").asDouble()).isGreaterThan(0);
        // Reading the same snapshot must not drain the counters a second time.
        assertThat(readMetrics()).isEqualTo(snapshot);
        var next = sample();
        assertEndpoint(next, "holds", 0, Map.of());
        assertEndpoint(next, "seats", 0, Map.of());
        assertThat(metrics.counters().get("holdAttempts")).isEqualTo(2);
    }

    @Test
    void rateUsesActualElapsedIntervalWhileStatusCountsStayIntegers() throws Exception {
        for (int i = 0; i < 3; i++) mvc.perform(get("/seats"));
        clock.advance(Duration.ofSeconds(2));
        snapshots.runOnce();
        assertEndpoint(readMetrics(), "seats", 1.5, Map.of("2xx", 3));
    }

    @Test
    void seatConflictsUseSlidingTwoSecondsWithoutLosingCumulativeCounts() throws Exception {
        hold("owner", 1, null);
        holdResponse("conflict-1", 1, null);
        holdResponse("conflict-2", 1, null);
        long second = hold("second-owner", 2, null);
        holdResponse("conflict-3", 2, null);
        // A user-limit 409 is not a SEAT_UNAVAILABLE conflict.
        assertThat(holdResponse("second-owner", 1, null).getResponse().getStatus()).isEqualTo(409);
        var first = sample();
        assertThat(first.get("seatConflicts").get("1").asInt()).isEqualTo(2);
        assertThat(first.get("seatConflicts").get("2").asInt()).isEqualTo(1);
        assertThat(metrics.counters().get("holdConflicts")).isEqualTo(3);
        holdResponse("conflict-4", 2, null);
        var next = sample(); // t=2: t=0 conflicts leave the (now-2s,now] window.
        assertThat(next.get("seatConflicts").has("1")).isFalse();
        assertThat(next.get("seatConflicts").get("2").asInt()).isEqualTo(1);
        assertThat(sample().get("seatConflicts").isEmpty()).isTrue();
        assertThat(metrics.seatConflictCount(1)).isEqualTo(2);
        assertThat(metrics.seatConflictCount(2)).isEqualTo(2);
        assertThat(second).isPositive();
    }

    @Test
    void phaseTransitionsAndZeroAvailabilityHistorySurviveReleaseUntilReset() throws Exception {
        assertThat(readMetrics().get("phase").asText()).isEqualTo("OPEN");
        long first = hold("u-1", 1, null);
        assertThat(sample().get("phase").asText()).isEqualTo("RUSH");
        long second = hold("u-2", 2, null);
        assertThat(sample().get("phase").asText()).isEqualTo("RESALE");
        postJson("/holds/" + second + "/release", Map.of("userId", "u-2"), null);
        assertThat(sample().get("phase").asText()).isEqualTo("RESALE");
        long replacement = hold("u-3", 2, null);
        approve(first, "u-1", null);
        approve(replacement, "u-3", null);
        var sold = sample();
        assertThat(sold.get("phase").asText()).isEqualTo("SOLD_OUT");
        assertThat(sold.get("seatMap").asText()).isEqualTo("SS");
        resetConfig(false);
        var reset = readMetrics();
        assertThat(reset.get("phase").asText()).isEqualTo("OPEN");
        assertThat(reset.get("seatMap").asText()).isEqualTo("AA");
        assertThat(reset.get("seatConflicts").isEmpty()).isTrue();
        assertThat(reset.get("pg").get("confirmAvgMs").asDouble()).isZero();
        assertThat(reset.get("schedulers").get("expiry").get("lastRunAt").isNull()).isTrue();
    }



    @Test
    void zeroAvailabilityHistoryAlsoSurvivesBetweenSamplesWithQueueDisabled() throws Exception {
        long held = hold("u-1", 1, null);
        hold("u-2", 2, null);
        postJson("/holds/" + held + "/release", Map.of("userId", "u-1"), null);
        var snapshot = sample();
        assertThat(snapshot.get("seatMap").asText()).isEqualTo("AH");
        assertThat(snapshot.get("phase").asText()).isEqualTo("RESALE");
        resetConfig(false);
        assertThat(readMetrics().get("phase").asText()).isEqualTo("OPEN");
    }

    @Test
    void heldRemainingIncludesConfirmingAndKeepsNegativeExpiredTtl() throws Exception {
        long held = hold("u-1", 1, null);
        var first = sample();
        assertThat(first.get("seatMap").asText()).isEqualTo("HA");
        assertThat(first.get("heldRemainingMs").get("1").asLong()).isEqualTo(29000);
        PG.confirmCode = 503;
        PG.lookupCode = 503;
        assertThat(approve(held, "u-1", null).getResponse().getStatus()).isEqualTo(202);
        clock.advance(Duration.ofSeconds(30));
        snapshots.runOnce();
        assertThat(readMetrics().get("heldRemainingMs").get("1").asLong()).isEqualTo(-1000);
        PG.lookupCode = 200;
        assertThat(recovery.runOnce()).isEqualTo(1);
        var recovered = sample();
        assertThat(recovered.get("heldRemainingMs").isEmpty()).isTrue();
        assertThat(recovered.get("schedulers").get("recovery").get("recovered").asInt()).isEqualTo(1);
        assertThat(recovered.get("schedulers").get("recovery").get("lastRunAt").asText()).isEqualTo(NOW.plusSeconds(31).toString());
    }

    @Test
    void pendingApprovalShowsRequestAndPgInflightWithoutBorrowingDatabaseConnection() throws Exception {
        long held = hold("u-1", 1, null);
        PG.entered = new CountDownLatch(1);
        PG.proceed = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var approved = pool.submit(() -> approve(held, "u-1", null));
            try {
                assertThat(PG.entered.await(5, TimeUnit.SECONDS)).isTrue();
                var pending = sample();
                assertThat(pending.get("endpoints").get("confirm").get("inflight").asInt()).isEqualTo(1);
                assertThat(pending.get("endpoints").get("confirm").get("rps").asDouble()).isZero();
                assertThat(pending.get("inflightTotal").asInt()).isEqualTo(1);
                assertThat(pending.get("pg").get("confirmInflight").asInt()).isEqualTo(1);
                assertThat(pending.get("pool").get("active").asInt()).isZero();
                PG.proceed.countDown();
                assertThat(approved.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
                var finished = sample();
                assertEndpoint(finished, "confirm", 1, Map.of("2xx", 1));
                assertThat(finished.get("pg").get("confirmInflight").asInt()).isZero();
                assertThat(finished.get("pg").get("confirmAvgMs").asDouble()).isGreaterThan(0);
                assertThat(sample().get("pg").get("confirmAvgMs").asDouble()).isZero();
            } finally { PG.proceed.countDown(); }
        }
    }

    @Test
    void schedulerMetricsReportLastRunAndCommittedCountsIncludingZero() throws Exception {
        resetConfig(true);
        hold("u-1", 1, null);
        clock.advance(Duration.ofSeconds(30));
        assertThat(expiry.runOnce()).isEqualTo(1);
        assertThat(recovery.runOnce()).isZero();
        var snapshot = sample();
        assertThat(snapshot.get("schedulers").get("expiry").get("expired").asInt()).isEqualTo(1);
        assertThat(snapshot.get("schedulers").get("recovery").get("recovered").asInt()).isZero();
        assertThat(snapshot.get("schedulers").get("recovery").get("lastRunAt").asText()).isEqualTo(NOW.plusSeconds(30).toString());
        assertThat(expiry.runOnce()).isZero();
        assertThat(sample().get("schedulers").get("expiry").get("expired").asInt()).isZero();
    }

    @Test
    void postgresLockWaitAndHikariUsageAppearInSnapshot() throws Exception {
        try (var owner = datasource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            owner.setAutoCommit(false);
            try (var statement = owner.createStatement()) { statement.executeUpdate("UPDATE seats SET version=version+1 WHERE id=1"); }
            var waiter = pool.submit(() -> jdbc.queryForObject("SELECT id FROM seats WHERE id=1 FOR UPDATE", Long.class));
            try {
                await(() -> repository.lockWaits() >= 1);
                var snapshot = sample();
                assertThat(snapshot.get("db").get("lockWaits").asLong()).isGreaterThanOrEqualTo(1);
                assertThat(snapshot.get("pool").get("active").asInt()).isGreaterThanOrEqualTo(2);
                assertThat(snapshot.get("pool").get("max").asInt()).isEqualTo(20);
            } finally { owner.commit(); }
            assertThat(waiter.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
    }

    @Test
    void failedMetricQueriesKeepPreviousValuesAndRequestCountersStillAdvance() throws Exception {
        hold("u-1", 1, null);
        var previous = sample();
        doThrow(new DataAccessResourceFailureException("sampling failed")).when(repository).seats(any(Instant.class));
        doThrow(new DataAccessResourceFailureException("sampling failed")).when(repository).lockWaits();
        assertThat(mvc.perform(get("/seats")).andReturn().getResponse().getStatus()).isEqualTo(200);
        var snapshot = sample();
        for (String field : List.of("seatMap", "heldRemainingMs", "db", "phase")) assertThat(snapshot.get(field)).isEqualTo(previous.get(field));
        assertEndpoint(snapshot, "seats", 1, Map.of("2xx", 1));
    }

    @Test
    void snapshotReadsDoNotBorrowConnectionsOrDrainRequestWindow() throws Exception {
        mvc.perform(get("/seats"));
        var expected = sample();
        clearInvocations(datasource);
        for (int i = 0; i < 20; i++) assertThat(readMetrics()).isEqualTo(expected);
        verify(datasource, never()).getConnection();
        verify(datasource, never()).getConnection(anyString(), anyString());
        assertEndpoint(sample(), "seats", 0, Map.of());
    }

    @Test
    void corsAllowsConfiguredOriginOnlyForAdminGetIncludingSse() throws Exception {
        for (String path : List.of("/admin/stats", "/admin/metrics", "/admin/metrics/stream")) {
            var response = mvc.perform(options(path).header("Origin", "http://localhost:8090")
                    .header("Access-Control-Request-Method", "GET")).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:8090");
        }
        assertThat(mvc.perform(get("/admin/metrics").header("Origin", "http://localhost:8090")).andReturn()
                .getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:8090");
        assertThat(mvc.perform(get("/admin/metrics").header("Origin", "http://unlisted.example")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(options("/admin/reset").header("Origin", "http://localhost:8090")
                .header("Access-Control-Request-Method", "POST")).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void sseSendsNamedMetricsEventInitiallyAndOnEveryPublishedSnapshot() throws Exception {
        var response = mvc.perform(get("/admin/metrics/stream").accept(MediaType.TEXT_EVENT_STREAM)
                .header("Origin", "http://localhost:8090")).andReturn();
        assertThat(response.getRequest().isAsyncStarted()).isTrue();
        try {
            await(() -> response.getResponse().getContentType() != null && content(response).contains("event:metrics"));
            assertThat(response.getResponse().getContentType()).startsWith("text/event-stream");
            assertThat(content(response)).contains("\"phase\":\"OPEN\"");
            hold("u-1", 1, null);
            sample();
            await(() -> content(response).contains("\"phase\":\"RUSH\""));
            var events = content(response).split("\n\n");
            assertThat(events.length).isEqualTo(2);
            assertThat(events[1]).contains("event:metrics", "data:", "2026-10-02T09:00:01Z");
        } finally { response.getRequest().getAsyncContext().complete(); }
    }

    private void resetConfig(boolean queue) throws Exception {
        var response = postJson("/admin/reset", Map.ofEntries(Map.entry("rows", 1), Map.entry("cols", 2),
                Map.entry("grades", List.of(Map.of("name", "VIP", "rows", 1, "price", 120000))),
                Map.entry("holdTtlSec", 30), Map.entry("confirmDeadlineSec", 5), Map.entry("strategy", "conditional"),
                Map.entry("dbBackstop", true), Map.entry("closeQueueOnSoldOut", false)), null);
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
    }
    private MvcResult holdResponse(String user, int seat, UUID token) throws Exception {
        var request = post("/holds").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID().toString())
                .content(json.writeValueAsString(Map.of("userId", user, "seatId", seat)));
        if (token != null) request.header("X-Admission-Key", token);
        return mvc.perform(request).andReturn();
    }
    private long hold(String user, int seat, UUID token) throws Exception {
        var response = holdResponse(user, seat, token);
        assertThat(response.getResponse().getStatus()).isEqualTo(201);
        return body(response).get("reservationId").asLong();
    }
    private MvcResult approve(long id, String user, UUID token) throws Exception {
        var order = body(postJson("/holds/" + id + "/checkout", Map.of("userId", user), token));
        return postJson("/payments/confirm", Map.of("userId", user, "orderId", order.get("orderId").asText(),
                "paymentKey", "key-" + order.get("orderId").asText(), "amount", order.get("amount").asInt()), token);
    }
    private MvcResult postJson(String path, Object content, UUID token) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(content));
        if (token != null) request.header("X-Admission-Key", token);
        return mvc.perform(request).andReturn();
    }
    private JsonNode sample() throws Exception { clock.advance(Duration.ofSeconds(1)); snapshots.runOnce(); return readMetrics(); }
    private JsonNode readMetrics() throws Exception {
        var response = mvc.perform(get("/admin/metrics")).andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        return body(response);
    }
    private JsonNode body(MvcResult result) { return json.readTree(result.getResponse().getContentAsByteArray()); }
    private static String content(MvcResult result) { return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8); }
    private void assertEndpoint(JsonNode snapshot, String endpoint, double rps, Map<String, Integer> counts) {
        var actual = snapshot.get("endpoints").get(endpoint);
        assertThat(actual.get("rps").asDouble()).isEqualTo(rps);
        assertThat(actual.get("status").size()).isEqualTo(counts.size());
        counts.forEach((status, count) -> assertThat(actual.get("status").get(status).asInt()).isEqualTo(count));
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    private static final class PgStub implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        volatile int confirmCode = 200;
        volatile int lookupCode = 200;
        volatile CountDownLatch entered;
        volatile CountDownLatch proceed;
        PgStub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(workers);
                server.createContext("/payments", exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    boolean confirm = exchange.getRequestURI().getPath().equals("/payments/confirm");
                    if (confirm && entered != null) {
                        entered.countDown();
                        try { proceed.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException interruption) { Thread.currentThread().interrupt(); }
                    }
                    byte[] body = "{\"status\":\"DONE\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(confirm ? confirmCode : lookupCode, body.length);
                    try (var output = exchange.getResponseBody()) { output.write(body); }
                    exchange.close();
                });
                server.start();
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void reset() { confirmCode = 200; lookupCode = 200; entered = null; proceed = null; }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }
}
