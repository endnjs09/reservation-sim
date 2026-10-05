package dev.endnjs.reservation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import dev.endnjs.reservation.expiry.ExpiryScheduler;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.payment.ConfirmRecoveryScheduler;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"reservation.scheduler.enabled=false", "reservation.scheduler.expiry-batch-size=2", "reservation.time-scale=1"})
@AutoConfigureMockMvc
@Testcontainers
@Import(S1IntegrationTest.TestClockConfig.class)
class S2IntegrationTest {
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
        registry.add("reservation.pg.connect-timeout-ms", () -> 500);
        registry.add("reservation.pg.request-timeout-ms", () -> 500);
    }
    @AfterAll static void stopPg() { PG.close(); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired ExpiryScheduler expiry;
    @Autowired ConfirmRecoveryScheduler recovery;
    @Autowired MetricsCollector metrics;
    @Autowired HikariDataSource datasource;

    @BeforeEach
    void reset() throws Exception {
        clock.set(NOW);
        PG.reset();
        var response = post("/admin/reset", Map.of("holdTtlSec", 180, "confirmDeadlineSec", 30,
                "strategy", "conditional", "dbBackstop", true));
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void holdAndReleaseTransitionsLeaveNoPgCalls() throws Exception {
        long id = hold("user", 1);
        assertState(id, "HELD", "HELD", null);
        assertThat(reservation(id).get("payment").isNull()).isTrue();
        var released = post("/holds/" + id + "/release", Map.of("userId", "user"));
        assertThat(body(released).get("status").asText()).isEqualTo("RELEASED");
        assertState(id, "RELEASED", "AVAILABLE", null);
        assertThat(PG.calls).isEmpty();
    }

    @Test
    void expiryHonorsExactDeadlineAndDrainsEveryBatch() throws Exception {
        long id = hold("user", 1);
        for (int i = 2; i <= 5; i++) hold("user-" + i, i);
        clock.advance(Duration.ofSeconds(179));
        assertThat(expiry.runOnce()).isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(expiry.runOnce()).isEqualTo(5);
        assertState(id, "EXPIRED", "AVAILABLE", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE status='EXPIRED'", Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE status='HELD'", Long.class)).isZero();
        assertThat(metrics.counters().get("expiredByScheduler")).isEqualTo(5);
        assertThat(expiry.runOnce()).isZero();
    }

    @Test
    void checkoutIsIdempotentAndDoesNotContactPg() throws Exception {
        long id = hold("user", 37);
        var first = post("/holds/" + id + "/checkout", Map.of("userId", "user"));
        var repeated = post("/holds/" + id + "/checkout", Map.of("userId", "user"));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(first.getResponse().getContentAsString()).isEqualTo(repeated.getResponse().getContentAsString());
        assertThat(body(first).get("amount").asInt()).isEqualTo(90000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Long.class)).isEqualTo(1);
        assertThat(reservation(id).get("payment").get("status").asText()).isEqualTo("REQUESTED");
        assertThat(PG.calls).isEmpty();
        error(post("/holds/" + id + "/checkout", Map.of("userId", "other")), 409, "RESERVATION_NOT_PAYABLE");
        clock.advance(Duration.ofSeconds(180));
        error(post("/holds/" + id + "/checkout", Map.of("userId", "user")), 409, "RESERVATION_NOT_PAYABLE");
        assertThat(PG.calls).isEmpty();
    }

    @Test
    void expiredConfirmIsRejectedWithoutApprovalAndWithoutImmediateStateChange() throws Exception {
        var order = order("user", 1);
        clock.advance(Duration.ofSeconds(180));
        error(confirm(order), 409, "RESERVATION_NOT_PAYABLE");
        assertState(order.reservationId(), "HELD", "HELD", "REQUESTED");
        assertThat(PG.calls).isEmpty();
        assertThat(metrics.counters().get("notPayable")).isEqualTo(1);
        assertThat(expiry.runOnce()).isEqualTo(1);
        assertState(order.reservationId(), "EXPIRED", "AVAILABLE", "REQUESTED");
    }

    @Test
    void invalidConfirmUserAmountOrderAndPaymentStatusMakeNoPgCall() throws Exception {
        var order = order("user", 1);
        error(confirm(new Order(order.reservationId(), "other", order.orderId(), order.amount())), 409, "RESERVATION_NOT_PAYABLE");
        error(confirm(new Order(order.reservationId(), "user", order.orderId(), order.amount() + 1)), 409, "RESERVATION_NOT_PAYABLE");
        error(confirm(new Order(order.reservationId(), "user", UUID.randomUUID(), order.amount())), 409, "RESERVATION_NOT_PAYABLE");
        jdbc.update("UPDATE payments SET status='FAILED' WHERE id=?", order.orderId());
        error(confirm(order), 409, "RESERVATION_NOT_PAYABLE");
        assertThat(PG.calls).isEmpty();
    }

    @Test
    void confirmCommitsConfirmingBeforeHttpAndExpiryProtectsIt() throws Exception {
        var order = order("user", 1);
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        PG.onConfirm = () -> {
            entered.countDown();
            try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Approval barrier timeout"); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> confirm(order));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(datasource.getHikariPoolMXBean().getActiveConnections()).isZero();
                assertState(order.reservationId(), "CONFIRMING", "HELD", "REQUESTED");
                assertThat(jdbc.queryForObject("SELECT confirm_deadline FROM reservations WHERE id=?",
                        Timestamp.class, order.reservationId()).toInstant()).isEqualTo(NOW.plusSeconds(30));
                clock.advance(Duration.ofSeconds(181));
                assertThat(expiry.runOnce()).isZero();
                assertState(order.reservationId(), "CONFIRMING", "HELD", "REQUESTED");
            } finally { proceed.countDown(); }
            assertThat(request.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        assertState(order.reservationId(), "CONFIRMED", "SOLD", "APPROVED");
    }

    @Test
    void approvedConfirmFinalizesReservationAndReservationApi() throws Exception {
        var order = order("user", 37);
        var response = confirm(order);
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(response).get("status").asText()).isEqualTo("CONFIRMED");
        assertState(order.reservationId(), "CONFIRMED", "SOLD", "APPROVED");
        var reservation = reservation(order.reservationId());
        assertThat(reservation.get("label").asText()).isEqualTo("D7");
        assertThat(reservation.get("grade").asText()).isEqualTo("A");
        assertThat(reservation.get("userId").asText()).isEqualTo("user");
        assertThat(reservation.get("payment").get("orderId").asText()).isEqualTo(order.orderId().toString());
        assertThat(jdbc.queryForObject("SELECT payment_key FROM payments WHERE id=?", String.class, order.orderId()))
                .isEqualTo("pk_test");
        assertThat(PG.approvalBody.get("orderId").asText()).isEqualTo(order.orderId().toString());
        assertThat(PG.approvalBody.get("amount").asInt()).isEqualTo(90000);
        assertThat(PG.calls).containsExactly("confirm");
    }

    @Test
    void declinedConfirmFailsAndReleasesSeat() throws Exception {
        var order = order("user", 1);
        PG.approval = new Reply(400, "{\"code\":\"INSUFFICIENT_FUNDS\"}", 0);
        error(confirm(order), 402, "PAYMENT_DECLINED");
        assertState(order.reservationId(), "PAYMENT_FAILED", "AVAILABLE", "FAILED");
        assertThat(jdbc.queryForObject("SELECT fail_reason FROM payments WHERE id=?", String.class, order.orderId()))
                .isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(PG.calls).containsExactly("confirm");
        assertThat(metrics.counters().get("declines")).isEqualTo(1);
    }

    @Test
    void approvalTimeoutWithDoneLookupFinalizesWithoutCancel() throws Exception {
        var order = order("user", 1);
        PG.approval = new Reply(200, "{\"status\":\"DONE\"}", 1200);
        PG.lookup = Reply.status("DONE");
        assertThat(confirm(order).getResponse().getStatus()).isEqualTo(200);
        assertState(order.reservationId(), "CONFIRMED", "SOLD", "APPROVED");
        assertThat(PG.calls).containsExactly("confirm", "lookup");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTHORIZED", "DECLINED"})
    void ambiguousUnapprovedPaymentIsCanceledBeforeSeatRelease(String status) throws Exception {
        var order = order("user", 1);
        PG.approval = new Reply(200, "{\"status\":\"DONE\"}", 1200);
        PG.lookup = Reply.status(status);
        PG.onCancel = () -> assertState(order.reservationId(), "CONFIRMING", "HELD", "REQUESTED");
        error(confirm(order), 402, "PAYMENT_DECLINED");
        assertState(order.reservationId(), "PAYMENT_FAILED", "AVAILABLE", "CANCELED");
        assertThat(PG.calls).containsExactly("confirm", "lookup", "cancel");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "MISSING"})
    void knownCanceledOrMissingPaymentFinishesWithoutCancelCall(String status) throws Exception {
        var order = order("user", 1);
        PG.approval = Reply.unavailable();
        PG.lookup = status.equals("MISSING") ? new Reply(404, "{}", 0) : Reply.status(status);
        error(confirm(order), 402, "PAYMENT_DECLINED");
        assertState(order.reservationId(), "PAYMENT_FAILED", "AVAILABLE", status.equals("MISSING") ? "FAILED" : "CANCELED");
        assertThat(PG.calls).containsExactly("confirm", "lookup");
    }

    @Test
    void lookupFailureReturns202AndRecoveryWaitsForDeadline() throws Exception {
        var order = pending();
        assertState(order.reservationId(), "CONFIRMING", "HELD", "REQUESTED");
        assertThat(PG.calls).containsExactly("confirm", "lookup");
        PG.lookup = Reply.status("DONE");
        clock.advance(Duration.ofSeconds(29));
        assertThat(recovery.runOnce()).isZero();
        assertThat(metrics.counters().get("recoveryAttempts")).isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(recovery.runOnce()).isEqualTo(1);
        assertState(order.reservationId(), "CONFIRMED", "SOLD", "APPROVED");
        assertThat(PG.calls).containsExactly("confirm", "lookup", "lookup");
        assertRecoveryCounters(1, 1);
        assertThat(recovery.runOnce()).isZero();
        assertRecoveryCounters(1, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DONE", "CANCELED", "MISSING", "AUTHORIZED", "DECLINED"})
    void recoveryFollowsEveryStatusTableRow(String status) throws Exception {
        var order = pending();
        PG.calls.clear();
        PG.lookup = status.equals("MISSING") ? new Reply(404, "{}", 0) : Reply.status(status);
        clock.advance(Duration.ofSeconds(30));
        assertThat(recovery.runOnce()).isEqualTo(1);
        if (status.equals("DONE")) assertState(order.reservationId(), "CONFIRMED", "SOLD", "APPROVED");
        else assertState(order.reservationId(), "PAYMENT_FAILED", "AVAILABLE", status.equals("MISSING") ? "FAILED" : "CANCELED");
        if (status.equals("AUTHORIZED") || status.equals("DECLINED")) assertThat(PG.calls).containsExactly("lookup", "cancel");
        else assertThat(PG.calls).containsExactly("lookup");
        assertRecoveryCounters(1, 1);
    }

    @Test
    void recoveryLookupFailuresKeepConfirmingAndHaveNoAttemptLimit() throws Exception {
        var order = pending();
        clock.advance(Duration.ofSeconds(181));
        assertThat(expiry.runOnce()).isZero();
        PG.calls.clear();
        for (int attempt = 1; attempt <= 6; attempt++) {
            assertThat(recovery.runOnce()).isZero();
            assertRecoveryCounters(attempt, 0);
            assertState(order.reservationId(), "CONFIRMING", "HELD", "REQUESTED");
        }
        assertThat(PG.calls).containsOnly("lookup").hasSize(6);
        PG.lookup = Reply.status("DONE");
        assertThat(recovery.runOnce()).isEqualTo(1);
        assertRecoveryCounters(7, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DONE", "CANCELED", "MISSING"})
    void failedCancelRecoveryStartsWithFreshLookupInsteadOfRepeatingCancel(String status) throws Exception {
        var order = order("user", 1);
        PG.approval = Reply.unavailable();
        PG.lookup = Reply.status("AUTHORIZED");
        PG.cancellation = Reply.unavailable();
        var response = confirm(order);
        assertThat(response.getResponse().getStatus()).isEqualTo(202);
        assertThat(body(response).get("status").asText()).isEqualTo("CONFIRMING");
        assertState(order.reservationId(), "CONFIRMING", "HELD", "REQUESTED");
        PG.lookup = status.equals("MISSING") ? new Reply(404, "{}", 0) : Reply.status(status);
        clock.advance(Duration.ofSeconds(181));
        assertThat(expiry.runOnce()).isZero();
        assertThat(recovery.runOnce()).isEqualTo(1);
        assertThat(PG.calls).containsExactly("confirm", "lookup", "cancel", "lookup");
        if (status.equals("DONE")) assertState(order.reservationId(), "CONFIRMED", "SOLD", "APPROVED");
        else assertState(order.reservationId(), "PAYMENT_FAILED", "AVAILABLE", status.equals("MISSING") ? "FAILED" : "CANCELED");
        assertRecoveryCounters(1, 1);
    }

    @Test
    void recoveryFailedCancelIsRetriedOnlyAfterFreshLookup() throws Exception {
        var order = pending();
        PG.lookup = Reply.status("AUTHORIZED");
        PG.cancellation = Reply.unavailable();
        PG.calls.clear();
        clock.advance(Duration.ofSeconds(30));
        assertThat(recovery.runOnce()).isZero();
        assertThat(recovery.runOnce()).isZero();
        assertState(order.reservationId(), "CONFIRMING", "HELD", "REQUESTED");
        assertRecoveryCounters(2, 0);
        PG.lookup = Reply.status("DECLINED");
        PG.cancellation = Reply.status("CANCELED");
        assertThat(recovery.runOnce()).isEqualTo(1);
        assertState(order.reservationId(), "PAYMENT_FAILED", "AVAILABLE", "CANCELED");
        assertThat(PG.calls).containsExactly("lookup", "cancel", "lookup", "cancel", "lookup", "cancel");
        assertRecoveryCounters(3, 1);
    }

    @Test
    void cancelTimeoutMaintainsSeatUntilCurrentCanceledStatusIsObserved() throws Exception {
        var order = order("user", 1);
        PG.approval = Reply.unavailable();
        PG.lookup = Reply.status("AUTHORIZED");
        PG.cancellation = new Reply(200, "{\"status\":\"CANCELED\"}", 1200);
        assertThat(confirm(order).getResponse().getStatus()).isEqualTo(202);
        assertState(order.reservationId(), "CONFIRMING", "HELD", "REQUESTED");
        PG.lookup = Reply.status("CANCELED");
        clock.advance(Duration.ofSeconds(30));
        assertThat(recovery.runOnce()).isEqualTo(1);
        assertThat(PG.calls).containsExactly("confirm", "lookup", "cancel", "lookup");
        assertState(order.reservationId(), "PAYMENT_FAILED", "AVAILABLE", "CANCELED");
    }

    @Test
    void oldExpiredReservationCannotChangeSeatOwnedByNewReservation() throws Exception {
        var old = order("old", 1);
        clock.advance(Duration.ofSeconds(180));
        expiry.runOnce();
        long newer = hold("new", 1);
        error(confirm(old), 409, "RESERVATION_NOT_PAYABLE");
        assertState(newer, "HELD", "HELD", null);
        assertThat(jdbc.queryForObject("SELECT current_reservation_id FROM seats WHERE id=1", Long.class)).isEqualTo(newer);
        assertThat(PG.calls).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "EXPIRED", "RELEASED", "PAYMENT_FAILED"})
    void terminalReservationRejectsConfirmAndOtherEventsPreserveState(String status) throws Exception {
        var order = order("user", 1);
        jdbc.update("UPDATE reservations SET status=? WHERE id=?", status, order.reservationId());
        jdbc.update("UPDATE seats SET status=?, current_reservation_id=? WHERE id=1",
                status.equals("CONFIRMED") ? "SOLD" : "AVAILABLE", status.equals("CONFIRMED") ? order.reservationId() : null);
        error(confirm(order), 409, "RESERVATION_NOT_PAYABLE");
        assertThat(body(post("/holds/" + order.reservationId() + "/release", Map.of("userId", "user")))
                .get("status").asText()).isEqualTo(status);
        clock.advance(Duration.ofSeconds(181));
        assertThat(expiry.runOnce()).isZero();
        assertThat(recovery.runOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, order.reservationId())).isEqualTo(status);
        assertThat(PG.calls).isEmpty();
    }

    @Test
    void confirmingRejectsRepeatedConfirmAndReleaseWithoutAnotherApproval() throws Exception {
        var order = pending();
        error(confirm(order), 409, "RESERVATION_NOT_PAYABLE");
        assertThat(body(post("/holds/" + order.reservationId() + "/release", Map.of("userId", "user")))
                .get("status").asText()).isEqualTo("CONFIRMING");
        assertThat(PG.calls).containsExactly("confirm", "lookup");
        assertState(order.reservationId(), "CONFIRMING", "HELD", "REQUESTED");
    }

    @Test
    void missingReservationReturns404() throws Exception {
        error(mvc.perform(MockMvcRequestBuilders.get("/reservations/999")).andReturn(), 404, "RESERVATION_NOT_FOUND");
        error(post("/holds/999/checkout", Map.of("userId", "user")), 404, "RESERVATION_NOT_FOUND");
    }

    @Test
    void expiryConditionalReleaseDoesNotOverwriteANewerOwner() throws Exception {
        post("/admin/reset", Map.of("dbBackstop", false));
        var old = order("old", 1);
        jdbc.update("UPDATE seats SET status='AVAILABLE', current_reservation_id=NULL WHERE id=1");
        long newer = hold("new", 1);
        jdbc.update("UPDATE reservations SET hold_expires_at=? WHERE id=?", Timestamp.from(NOW), old.reservationId());
        assertThat(expiry.runOnce()).isEqualTo(1);
        assertState(newer, "HELD", "HELD", null);
        assertThat(jdbc.queryForObject("SELECT current_reservation_id FROM seats WHERE id=1", Long.class)).isEqualTo(newer);
    }

    private Order pending() throws Exception {
        var order = order("user", 1);
        PG.approval = Reply.unavailable();
        PG.lookup = Reply.unavailable();
        var response = confirm(order);
        assertThat(response.getResponse().getStatus()).isEqualTo(202);
        assertThat(body(response).get("status").asText()).isEqualTo("CONFIRMING");
        return order;
    }
    private long hold(String userId, int seat) throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.post("/holds").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("userId", userId, "seatId", seat)))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return body(result).get("reservationId").asLong();
    }
    private Order order(String userId, int seat) throws Exception {
        long id = hold(userId, seat);
        var result = post("/holds/" + id + "/checkout", Map.of("userId", userId));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var body = body(result);
        return new Order(id, userId, UUID.fromString(body.get("orderId").asText()), body.get("amount").asInt());
    }
    private MvcResult confirm(Order order) throws Exception {
        return post("/payments/confirm", Map.of("userId", order.userId(), "orderId", order.orderId(),
                "paymentKey", "pk_test", "amount", order.amount()));
    }
    private MvcResult post(String path, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private JsonNode reservation(long id) throws Exception {
        return body(mvc.perform(MockMvcRequestBuilders.get("/reservations/" + id)).andReturn());
    }
    private void error(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(body(result).get("code").asText()).isEqualTo(code);
    }
    private void assertState(long id, String reservation, String seat, String payment) {
        assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, id)).isEqualTo(reservation);
        assertThat(jdbc.queryForObject("SELECT s.status FROM seats s JOIN reservations r ON r.seat_id=s.id WHERE r.id=?",
                String.class, id)).isEqualTo(seat);
        if (payment != null) assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE reservation_id=?", String.class, id)).isEqualTo(payment);
    }
    private void assertRecoveryCounters(long attempts, long recovered) {
        assertThat(metrics.counters().get("recoveryAttempts")).isEqualTo(attempts);
        assertThat(metrics.counters().get("recovered")).isEqualTo(recovered);
    }
    private record Order(long reservationId, String userId, UUID orderId, int amount) {}
    private record Reply(int status, String body, long delayMs) {
        static Reply status(String status) { return new Reply(200, "{\"status\":\"" + status + "\"}", 0); }
        static Reply unavailable() { return new Reply(503, "{}", 0); }
    }
    private static final class PgStub implements AutoCloseable {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final ExecutorService executor = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
        final HttpServer http;
        volatile Reply approval, lookup, cancellation;
        volatile JsonNode approvalBody;
        volatile Runnable onConfirm, onCancel;
        PgStub() {
            try { http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }
            catch (IOException exception) { throw new IllegalStateException(exception); }
            http.createContext("/payments", this::handle);
            http.setExecutor(executor);
            reset();
            http.start();
        }
        String baseUrl() { return "http://127.0.0.1:" + http.getAddress().getPort(); }
        void reset() {
            calls.clear(); approvalBody = null;
            approval = Reply.status("DONE"); lookup = Reply.status("DONE"); cancellation = Reply.status("CANCELED");
            onConfirm = () -> {}; onCancel = () -> {};
        }
        void handle(HttpExchange exchange) throws IOException {
            Reply reply;
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/payments/confirm")) {
                    calls.add("confirm");
                    approvalBody = JsonMapper.builder().build().readTree(exchange.getRequestBody().readAllBytes());
                    reply = approval;
                    onConfirm.run();
                } else if (path.endsWith("/cancel")) {
                    calls.add("cancel"); reply = cancellation; onCancel.run();
                } else { calls.add("lookup"); reply = lookup; }
                try { Thread.sleep(reply.delayMs()); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException disconnected) {
                // Expected when a test deliberately exceeds the PG client's timeout.
            }
        }
        @Override public void close() { http.stop(0); executor.shutdownNow(); }
    }
}
