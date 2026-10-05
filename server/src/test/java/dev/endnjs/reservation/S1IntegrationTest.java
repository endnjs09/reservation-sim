package dev.endnjs.reservation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import dev.endnjs.reservation.metrics.MetricsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"reservation.scheduler.enabled=false", "reservation.time-scale=1"})
@AutoConfigureMockMvc
@Testcontainers
@Import(S1IntegrationTest.TestClockConfig.class)
class S1IntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("reservation").withUsername("reservation").withPassword("reservation");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("internal.notifications-enabled",()->false);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("admission.required", () -> false);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(NOW); }
        // Existing deterministic fixtures advance business time and measurement time together.
        // Wall-clock correction is tested independently with a separate ticker in W2HistogramsTest.
        @Bean @Primary java.util.function.LongSupplier testMetricsNanoTime(MutableClock clock) {
            return () -> java.time.Duration.between(Instant.EPOCH,clock.instant()).toNanos();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricsCollector metrics;
    @Autowired MutableClock clock;

    @BeforeEach
    void reset() throws Exception {
        clock.set(NOW);
        var body = Map.ofEntries(
                Map.entry("rows", 10), Map.entry("cols", 10),
                Map.entry("grades", List.of(grade("VIP", 1, 150000), grade("S", 2, 120000),
                        grade("A", 3, 90000), grade("B", 4, 60000))),
                Map.entry("holdTtlSec", 180), Map.entry("strategy", "conditional"), Map.entry("dbBackstop", true),
                Map.entry("closeQueueOnSoldOut", false), Map.entry("confirmDeadlineSec", 30));
        assertThat(post("/admin/reset", body).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void holdReturnsGradePriceExpiryAndSeatState() throws Exception {
        var response = hold("user", 37, "first");
        assertThat(response.getResponse().getStatus()).isEqualTo(201);
        var body = body(response);
        assertThat(body.get("seatId").asLong()).isEqualTo(37);
        assertThat(body.get("grade").asText()).isEqualTo("A");
        assertThat(body.get("price").asInt()).isEqualTo(90000);
        assertThat(body.get("status").asText()).isEqualTo("HELD");
        assertThat(Instant.parse(body.get("holdExpiresAt").asText())).isEqualTo(NOW.plusSeconds(180));
        assertThat(jdbc.queryForObject("SELECT current_reservation_id FROM seats WHERE id=37", Long.class))
                .isEqualTo(body.get("reservationId").asLong());
        var seats = body(mvc.perform(MockMvcRequestBuilders.get("/seats")).andReturn());
        assertThat(seats.get("seats").size()).isEqualTo(100);
        assertThat(seats.get("seats").get(36).get("status").asText()).isEqualTo("HELD");
    }

    @Test
    void rejectsMissingSeatOccupiedSeatAndSecondHold() throws Exception {
        error(hold("missing", 101, "missing"), 404, "SEAT_NOT_FOUND");
        assertThat(hold("user", 1, "first").getResponse().getStatus()).isEqualTo(201);
        error(hold("other", 1, "occupied"), 409, "SEAT_UNAVAILABLE");
        error(hold("user", 2, "second"), 409, "USER_ALREADY_HOLDING");
        assertThat(metrics.seatConflictCount(1)).isEqualTo(1);
    }

    @Test
    void releaseIsIdempotentAndAllowsNewHold() throws Exception {
        long id = body(hold("user", 1, "first")).get("reservationId").asLong();
        var first = post("/holds/" + id + "/release", Map.of("userId", "user"));
        var repeated = post("/holds/" + id + "/release", Map.of("userId", "user"));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(first).get("status").asText()).isEqualTo("RELEASED");
        assertThat(repeated.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=1", String.class)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT current_reservation_id FROM seats WHERE id=1", Long.class)).isNull();
        assertThat(hold("user", 1, "new-key").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void confirmedUserCannotPurchaseAgain() throws Exception {
        long id = body(hold("user", 1, "first")).get("reservationId").asLong();
        jdbc.update("UPDATE reservations SET status='CONFIRMED' WHERE id=?", id);
        jdbc.update("UPDATE seats SET status='SOLD' WHERE id=1");
        error(hold("user", 2, "another"), 409, "USER_ALREADY_PURCHASED");
    }

    @Test
    void idempotencyPreservesOriginalResponseEvenAfterRelease() throws Exception {
        var original = hold("user", 1, "same");
        var repeated = hold("user", 1, "same");
        assertThat(repeated.getResponse().getStatus()).isEqualTo(201);
        assertThat(repeated.getResponse().getContentAsString()).isEqualTo(original.getResponse().getContentAsString());
        error(hold("user", 2, "same"), 422, "IDEMPOTENCY_KEY_REUSED");
        error(hold("other", 1, "same"), 422, "IDEMPOTENCY_KEY_REUSED");
        long id = body(original).get("reservationId").asLong();
        post("/holds/" + id + "/release", Map.of("userId", "user"));
        assertThat(hold("user", 1, "same").getResponse().getContentAsString())
                .isEqualTo(original.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"conditional", "pessimistic", "optimistic"})
    void oneWinnerAnd199ConflictsWithoutBackstop(String strategy) throws Exception {
        configure(strategy, false);
        var results = concurrent(200, i -> holdUnchecked("user-" + i, 1, "key-" + i));
        assertThat(successes(results)).isEqualTo(1);
        for (var result : results) {
            if (result.getResponse().getStatus() != 201) error(result, 409, "SEAT_UNAVAILABLE");
        }
        assertThat(metrics.seatConflictCount(1)).isEqualTo(199);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"conditional", "pessimistic", "optimistic"})
    void oneHoldPerUserAcross50SeatsWithoutBackstop(String strategy) throws Exception {
        configure(strategy, false);
        var results = concurrent(50, i -> holdUnchecked("same-user", i + 1, "key-" + i));
        assertThat(successes(results)).isEqualTo(1);
        for (var result : results) {
            if (result.getResponse().getStatus() != 201) error(result, 409, "USER_ALREADY_HOLDING");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"conditional", "pessimistic", "optimistic", "naive"})
    void concurrentSameKeyReturnsOneReservationAndIdenticalBodies(String strategy) throws Exception {
        configure(strategy, false);
        var results = concurrent(30, i -> holdUnchecked("same-user", 1, "same-key"));
        assertThat(successes(results)).isEqualTo(30);
        String expected = results.getFirst().getResponse().getContentAsString();
        for (var result : results) assertThat(result.getResponse().getContentAsString()).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Long.class)).isEqualTo(1);
    }

    @Test
    void naiveWithoutBackstopPrintsObservedSuccesses() throws Exception {
        configure("naive", false);
        var results = concurrent(200, i -> holdUnchecked("user-" + i, 1, "key-" + i));
        System.out.println("naive + dbBackstop=false: " + successes(results) + " successful holds / 200");
    }

    @Test
    void backstopProtectsNaiveSeatAndUserRaces() throws Exception {
        configure("naive", true);
        var seatResults = concurrent(200, i -> holdUnchecked("user-" + i, 1, "seat-key-" + i));
        assertThat(successes(seatResults)).isEqualTo(1);
        for (var result : seatResults) {
            if (result.getResponse().getStatus() != 201) error(result, 409, "SEAT_UNAVAILABLE");
        }
        configure("naive", true);
        var userResults = concurrent(50, i -> holdUnchecked("same-user", i + 1, "user-key-" + i));
        assertThat(successes(userResults)).isEqualTo(1);
        for (var result : userResults) {
            if (result.getResponse().getStatus() != 201) error(result, 409, "USER_ALREADY_HOLDING");
        }
        configure("conditional", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN "
                + "('ux_rs_seat_active','ux_res_user_active')", Long.class)).isZero();
        configure("conditional", true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN "
                + "('ux_rs_seat_active','ux_res_user_active')", Long.class)).isEqualTo(2);
    }

    @Test
    void resetRebuildsLayoutClearsTablesAndCountersAndPreservesUnspecifiedSettings() throws Exception {
        long reservationId = body(hold("user", 1, "first")).get("reservationId").asLong();
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("INSERT INTO payments(id,reservation_id,amount,status,created_at,updated_at) "
                + "VALUES (?,?,150000,'REQUESTED',?,?)", UUID.randomUUID(), reservationId, now, now);
        error(hold("other", 1, "conflict"), 409, "SEAT_UNAVAILABLE");
        var response = post("/admin/reset", Map.of("rows", 4, "cols", 3, "holdTtlSec", 90,
                "grades", List.of(grade("VIP", 1, 150000), grade("S", 1, 120000),
                        grade("A", 1, 90000), grade("B", 1, 60000))));
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(response).get("config").get("strategy").asText()).isEqualTo("conditional");
        var seats = body(mvc.perform(MockMvcRequestBuilders.get("/seats")).andReturn()).get("seats");
        assertThat(seats.size()).isEqualTo(12);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 3; col++) {
                var seat = seats.get(row * 3 + col);
                assertThat(seat.get("id").asLong()).isEqualTo(row * 3 + col + 1);
                assertThat(seat.get("label").asText()).isEqualTo("" + (char) ('A' + row) + (col + 1));
                assertThat(seat.get("grade").asText()).isEqualTo(List.of("VIP", "S", "A", "B").get(row));
            }
        }
        for (String table : List.of("reservations", "payments")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
        }
        assertThat(metrics.counters().values()).containsOnly(0L);
        assertThat(metrics.seatConflictCount(1)).isZero();
        var stats = body(mvc.perform(MockMvcRequestBuilders.get("/admin/stats")).andReturn());
        assertThat(stats.get("seats").get("total").asInt()).isEqualTo(12);
        assertThat(stats.get("seats").get("AVAILABLE").asInt()).isEqualTo(12);
        assertThat(stats.get("seats").get("byGrade").get("VIP").get("AVAILABLE").asInt()).isEqualTo(3);
        assertThat(stats.get("reservations").get("CONFIRMING").asInt()).isZero();
        assertThat(stats.get("payments").get("APPROVED").asInt()).isZero();
        assertThat(stats.has("queue")).isFalse();
        assertThat(body(post("/admin/reset", Map.of())).get("config").get("holdTtlSec").asInt()).isEqualTo(90);
    }

    @Test
    void invalidGradesReturn400WithoutChangingCurrentData() throws Exception {
        hold("user", 1, "first");
        error(post("/admin/reset", Map.of("rows", 5)), 400, "VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats", Long.class)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Long.class)).isEqualTo(1);
    }

    @Test
    void malformedHoldRequestsReturn400() throws Exception {
        error(post("/holds", Map.of("userId", "user", "seatId", 1)), 400, "VALIDATION_FAILED");
        error(hold("", 1, "key"), 400, "VALIDATION_FAILED");
        error(hold("user", 0, "key"), 400, "VALIDATION_FAILED");
        error(hold("user", 1, "x".repeat(65)), 400, "VALIDATION_FAILED");
    }

    private void configure(String strategy, boolean backstop) throws Exception {
        assertThat(post("/admin/reset", Map.of("strategy", strategy, "dbBackstop", backstop))
                .getResponse().getStatus()).isEqualTo(200);
    }
    private static Map<String, Object> grade(String name, int rows, int price) {
        return Map.of("name", name, "rows", rows, "price", price);
    }
    private MvcResult hold(String userId, long seatId, String key) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/holds").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", userId, "seatId", seatId)))).andReturn();
    }
    private MvcResult holdUnchecked(String userId, long seatId, String key) {
        try { return hold(userId, seatId, key); }
        catch (Exception exception) { throw new RuntimeException(exception); }
    }
    private MvcResult post(String path, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }
    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
    private void error(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(body(result).get("code").asText()).isEqualTo(code);
    }
    private static long successes(List<MvcResult> results) {
        return results.stream().filter(r -> r.getResponse().getStatus() == 201).count();
    }
    private static List<MvcResult> concurrent(int count, IntFunction<MvcResult> request) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(count)) {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
                    return request.apply(index);
                }));
            }
            try {
                assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<MvcResult> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        }
    }
}
