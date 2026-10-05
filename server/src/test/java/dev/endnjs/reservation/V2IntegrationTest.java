package dev.endnjs.reservation;

import dev.endnjs.reservation.expiry.ExpiryScheduler;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.payment.ConfirmRecoveryScheduler;
import dev.endnjs.reservation.payment.PgClient;
import dev.endnjs.reservation.payment.PgUnavailableException;
import dev.endnjs.reservation.sale.SaleEndScheduler;
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
import org.junit.jupiter.params.provider.CsvSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = "reservation.scheduler.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@Import(S1IntegrationTest.TestClockConfig.class)
class V2IntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-03T02:00:00Z");
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
    @Autowired MetricsCollector metrics;
    @Autowired ExpiryScheduler expiry;
    @Autowired ConfirmRecoveryScheduler recovery;
    @Autowired SaleEndScheduler saleEnd;
    @MockitoBean PgClient pg;

    @BeforeEach void reset() throws Exception {
        clock.set(NOW);
        configure(Map.of());
        when(pg.confirm(anyString(), any(UUID.class), anyInt())).thenReturn(new PgClient.Approval(true, null));
    }

    @Test void twoSeatsPreserveRepresentativeAndReturnFullPriceAndQuery() throws Exception {
        var held = body(hold("user", List.of(11, 1), "pair"));
        assertThat(held.get("seatId").asLong()).isEqualTo(1);
        assertThat(held.get("price").asInt()).isEqualTo(150000);
        assertThat(held.get("totalPrice").asInt()).isEqualTo(270000);
        assertThat(held.get("seats").get(0).get("id").asLong()).isEqualTo(1);
        assertThat(held.get("seats").get(1).get("label").asText()).isEqualTo("B1");
        long id = held.get("reservationId").asLong();
        assertThat(jdbc.queryForObject("SELECT seat_count FROM reservations WHERE id=?", Integer.class, id)).isEqualTo(2);
        assertOwned(id, List.of(1, 11), "HELD", false);
        var query = body(mvc.perform(get("/reservations/" + id)).andReturn());
        assertThat(query.get("seats")).isEqualTo(held.get("seats"));
        assertThat(query.get("totalPrice")).isEqualTo(held.get("totalPrice"));
        var checkout = body(post("/holds/" + id + "/checkout", Map.of("userId", "user")));
        assertThat(checkout.get("amount").asInt()).isEqualTo(270000);
        assertThat(post("/holds/" + id + "/checkout", Map.of("userId", "user")).getResponse().getContentAsString())
                .isEqualTo(json.writeValueAsString(checkout));
    }

    @ParameterizedTest @ValueSource(strings = {"conditional", "pessimistic", "optimistic", "naive"})
    void unavailableSeatsRollBackAllAndCountOnlyThoseSeats(String strategy) throws Exception {
        configure(Map.of("strategy", strategy, "dbBackstop", false));
        hold("owner", List.of(2, 3), "owned");
        var failure = hold("other", List.of(3, 1, 2), "loser");
        error(failure, 409, "SEAT_UNAVAILABLE");
        assertThat(body(failure).get("unavailableSeatIds")).isEqualTo(json.valueToTree(List.of(2, 3)));
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=1", String.class)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT version FROM seats WHERE id=1", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_seats", Long.class)).isEqualTo(2);
        assertThat(metrics.seatConflictCount(1)).isZero();
        assertThat(metrics.seatConflictCount(2)).isEqualTo(1);
        assertThat(metrics.seatConflictCount(3)).isEqualTo(1);
        assertThat(metrics.counters().get("holdSuccess")).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({"conditional,false", "pessimistic,false", "optimistic,false",
            "conditional,true", "pessimistic,true", "optimistic,true", "naive,true"})
    void reverseOrder200RequestsHaveOneWholeReservationAndNoDeadlocks(String strategy, boolean backstop) throws Exception {
        configure(Map.of("strategy", strategy, "dbBackstop", backstop));
        var results = concurrent(200, i -> holdUnchecked("u-" + i,
                i % 2 == 0 ? List.of(1, 2) : List.of(2, 1), "k-" + i));
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201).count()).isEqualTo(1);
        for (var r : results) if (r.getResponse().getStatus() != 201) {
            error(r, 409, "SEAT_UNAVAILABLE");
            assertThat(body(r).get("unavailableSeatIds")).isEqualTo(json.valueToTree(List.of(1, 2)));
        }
        long id = jdbc.queryForObject("SELECT id FROM reservations", Long.class);
        assertOwned(id, List.of(1, 2), "HELD", false);
        assertThat(metrics.seatConflictCount(1)).isEqualTo(199);
        assertThat(metrics.seatConflictCount(2)).isEqualTo(199);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_seats", Long.class)).isEqualTo(2);
        System.out.println("V2 " + strategy + " backstop=" + backstop + ": 1 reservation / 200 reverse-order pair requests");
    }

    @ParameterizedTest @ValueSource(strings = {"conditional", "pessimistic", "optimistic"})
    void sameUserCannotOwnTwoReservationsWithoutBackstop(String strategy) throws Exception {
        configure(Map.of("strategy", strategy, "dbBackstop", false));
        var results = concurrent(40, i -> holdUnchecked("same", List.of(2 * i + 1, 2 * i + 2), "k-" + i));
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201).count()).isEqualTo(1);
        for (var r : results) if (r.getResponse().getStatus() != 201) error(r, 409, "USER_ALREADY_HOLDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE status='HELD'", Long.class)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"conditional", "pessimistic", "optimistic", "naive"})
    void concurrentCanonicalKeysReplayAndReleasePreservesOriginalBody(String strategy) throws Exception {
        configure(Map.of("strategy", strategy, "dbBackstop", false));
        var results = concurrent(30, i -> holdUnchecked("same", i % 2 == 0 ? List.of(1, 2) : List.of(2, 1), "same"));
        String original = results.getFirst().getResponse().getContentAsString();
        for (var r : results) {
            assertThat(r.getResponse().getStatus()).isEqualTo(201);
            assertThat(r.getResponse().getContentAsString()).isEqualTo(original);
        }
        long id = body(results.getFirst()).get("reservationId").asLong();
        post("/holds/" + id + "/release", Map.of("userId", "same"));
        assertOwned(id, List.of(1, 2), "AVAILABLE", true);
        assertThat(hold("same", List.of(2, 1), "same").getResponse().getContentAsString()).isEqualTo(original);
        error(hold("same", List.of(1, 3), "same"), 422, "IDEMPOTENCY_KEY_REUSED");
        error(hold("other", List.of(2, 1), "same"), 422, "IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Long.class)).isEqualTo(1);
    }

    @Test void singleSeatRequestIsCompatibleWithOneItemList() throws Exception {
        var original = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/holds").header("Idempotency-Key", "old")
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"user\",\"seatId\":1}")).andReturn();
        assertThat(original.getResponse().getStatus()).isEqualTo(201);
        assertThat(hold("user", List.of(1), "old").getResponse().getContentAsString())
                .isEqualTo(original.getResponse().getContentAsString());
        assertThat(body(original).get("totalPrice")).isEqualTo(body(original).get("price"));
    }

    @Test void validatesSelectionAndRuntimeLimitWithoutChangingState() throws Exception {
        for (String invalid : List.of("{}", "{\"seatId\":1,\"seatIds\":[1]}", "{\"seatIds\":[]}",
                "{\"seatIds\":[1,1]}", "{\"seatIds\":[0]}", "{\"seatIds\":[null]}", "{\"seatId\":-1}",
                "{\"seatIds\":[1,2,3,4,5]}")) {
            var node = json.readTree(invalid).deepCopy();
            ((tools.jackson.databind.node.ObjectNode) node).put("userId", "user");
            error(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/holds").header("Idempotency-Key", "invalid").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(node))).andReturn(), 400, "VALIDATION_FAILED");
        }
        error(hold("user", List.of(1, 101), "missing"), 404, "SEAT_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Long.class)).isZero();
        error(post("/admin/reset", Map.of("maxSeatsPerUser", 0)), 400, "VALIDATION_FAILED");
        configure(Map.of("maxSeatsPerUser", 2));
        error(hold("user", List.of(1, 2, 3), "three"), 400, "VALIDATION_FAILED");
        assertThat(hold("user", List.of(2, 1), "two").getResponse().getStatus()).isEqualTo(201);
    }

    @Test void rejectsTotalPriceOverflowBeforeAcquiringAnySeat() throws Exception {
        configure(Map.of("grades", List.of(Map.of("name", "VIP", "rows", 10, "price", 1500000000))));
        error(hold("user", List.of(1, 2), "overflow"), 400, "VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE status='AVAILABLE'", Long.class)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Long.class)).isZero();
        assertThat(hold("user", List.of(1), "single").getResponse().getStatus()).isEqualTo(201);
    }

    @ParameterizedTest @ValueSource(strings = {"CONFIRMED", "PENDING_DEPOSIT"})
    void purchasedUsersCannotHoldAgain(String status) throws Exception {
        long id = body(hold("user", List.of(1, 2), "owned")).get("reservationId").asLong();
        jdbc.update("UPDATE reservations SET status=? WHERE id=?", status, id);
        jdbc.update("UPDATE seats SET status=? WHERE id IN (1,2)", status.equals("CONFIRMED") ? "SOLD" : status);
        error(hold("user", List.of(3, 4), "new"), 409, "USER_ALREADY_PURCHASED");
    }

    @Test void approveSellsEverySeatAndRejectsIncorrectTotal() throws Exception {
        var order = order();
        error(confirm(order, order.amount() - 1), 409, "RESERVATION_NOT_PAYABLE");
        verifyNoInteractions(pg);
        assertThat(confirm(order, order.amount()).getResponse().getStatus()).isEqualTo(200);
        verify(pg).confirm("card", order.orderId(), 270000);
        assertOwned(order.id(), List.of(1, 11), "SOLD", false);
        assertReservation(order.id(), "CONFIRMED");
        error(hold("user", List.of(2, 3), "second"), 409, "USER_ALREADY_PURCHASED");
    }

    @Test void declineReleasesAllMembershipsAndAllowsReholdWithBackstop() throws Exception {
        var order = order();
        doReturn(new PgClient.Approval(false, "DECLINED")).when(pg).confirm(anyString(), any(UUID.class), anyInt());
        error(confirm(order, order.amount()), 402, "PAYMENT_DECLINED");
        assertOwned(order.id(), List.of(1, 11), "AVAILABLE", true);
        assertReservation(order.id(), "PAYMENT_FAILED");
        assertThat(hold("user", List.of(11, 1), "retry").getResponse().getStatus()).isEqualTo(201);
    }

    @ParameterizedTest @ValueSource(strings = {"DONE", "CANCELED", "MISSING", "AUTHORIZED", "DECLINED"})
    void uncertainApprovalResolvesWholeReservationFromLookup(String lookup) throws Exception {
        var order = order();
        doThrow(new PgUnavailableException("unknown")).when(pg).confirm(anyString(), any(UUID.class), anyInt());
        when(pg.status("card")).thenReturn(PgClient.Status.valueOf(lookup));
        var response = confirm(order, order.amount());
        boolean approved = lookup.equals("DONE");
        assertThat(response.getResponse().getStatus()).isEqualTo(approved ? 200 : 402);
        assertOwned(order.id(), List.of(1, 11), approved ? "SOLD" : "AVAILABLE", !approved);
        verify(pg).status("card");
        if (lookup.equals("AUTHORIZED") || lookup.equals("DECLINED")) verify(pg).cancel("card");
    }

    @ParameterizedTest @ValueSource(strings = {"DONE", "AUTHORIZED"})
    void failedCancellationStaysConfirmingAndRecoveryAlwaysLooksUpAgainAfterSaleEnd(String lookup) throws Exception {
        configure(Map.of("saleDurationSec", 20));
        var order = order();
        doThrow(new PgUnavailableException("unknown")).when(pg).confirm(anyString(), any(UUID.class), anyInt());
        when(pg.status("card")).thenReturn(PgClient.Status.AUTHORIZED);
        doThrow(new PgUnavailableException("cancel unavailable")).when(pg).cancel("card");
        assertThat(confirm(order, order.amount()).getResponse().getStatus()).isEqualTo(202);
        assertOwned(order.id(), List.of(1, 11), "HELD", false);
        clock.advance(Duration.ofSeconds(20));
        saleEnd.runOnce();
        assertReservation(order.id(), "CONFIRMING");
        doReturn(PgClient.Status.valueOf(lookup)).when(pg).status("card");
        doNothing().when(pg).cancel("card");
        clock.advance(Duration.ofSeconds(10));
        assertThat(recovery.runOnce()).isEqualTo(1);
        assertOwned(order.id(), List.of(1, 11), lookup.equals("DONE") ? "SOLD" : "AVAILABLE", !lookup.equals("DONE"));
        var inOrder = inOrder(pg);
        inOrder.verify(pg).confirm(anyString(), any(UUID.class), anyInt());
        inOrder.verify(pg).status("card");
        inOrder.verify(pg).cancel("card");
        inOrder.verify(pg).status("card");
        if (!lookup.equals("DONE")) inOrder.verify(pg).cancel("card");
        assertThat(recovery.runOnce()).isZero();
    }

    @Test void expiryAndSaleEndReleaseEverySeatAndRepeatedRunsAreIdempotent() throws Exception {
        long id = body(hold("user", List.of(1, 11), "ttl")).get("reservationId").asLong();
        clock.advance(Duration.ofSeconds(180));
        assertThat(expiry.runOnce()).isEqualTo(1);
        assertOwned(id, List.of(1, 11), "AVAILABLE", true);
        assertThat(expiry.runOnce()).isZero();
        configure(Map.of("saleDurationSec", 2));
        id = body(hold("user", List.of(1, 11), "end")).get("reservationId").asLong();
        clock.advance(Duration.ofSeconds(2));
        saleEnd.runOnce();
        assertReservation(id, "EXPIRED");
        assertOwned(id, List.of(1, 11), "AVAILABLE", true);
        saleEnd.runOnce();
        error(hold("other", List.of(1, 11), "late"), 409, "SALE_ENDED");
    }

    @ParameterizedTest @ValueSource(strings = {"conditional", "pessimistic", "optimistic"})
    void overlappingGroupsRollBackLosersEvenWhenTheirFirstSeatWasFree(String strategy) throws Exception {
        configure(Map.of("strategy", strategy, "dbBackstop", false));
        var results = concurrent(40, i -> holdUnchecked("u-" + i, List.of(i + 1, 100), "k-" + i));
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE status='HELD'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE status='AVAILABLE' AND version<>0", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_seats", Long.class)).isEqualTo(2);
        for (var r : results) if (r.getResponse().getStatus() != 201) {
            error(r, 409, "SEAT_UNAVAILABLE");
            assertThat(body(r).get("unavailableSeatIds")).isEqualTo(json.valueToTree(List.of(100)));
        }
        assertInvariants();
    }

    @Test void fourMixedGradeSeatsReachDefaultLimitAndReleaseTogether() throws Exception {
        var held = body(hold("user", List.of(61, 31, 11, 1), "four"));
        assertThat(held.get("seats").size()).isEqualTo(4);
        assertThat(held.get("totalPrice").asInt()).isEqualTo(420000);
        long id = held.get("reservationId").asLong();
        assertOwned(id, List.of(1, 11, 31, 61), "HELD", false);
        assertInvariants();
        post("/holds/" + id + "/release", Map.of("userId", "user"));
        assertOwned(id, List.of(1, 11, 31, 61), "AVAILABLE", true);
        assertInvariants();
    }

    @ParameterizedTest @ValueSource(strings = {"expiry", "release", "decline"})
    void returningOldGroupDoesNotOverwriteAnySeatOfANewerOwner(String operation) throws Exception {
        configure(Map.of("dbBackstop", false));
        var old = order();
        jdbc.update("UPDATE seats SET status='AVAILABLE',current_reservation_id=NULL WHERE id=11");
        long newer = body(hold("newer", List.of(11, 12), "newer")).get("reservationId").asLong();
        if (operation.equals("expiry")) {
            jdbc.update("UPDATE reservations SET hold_expires_at=? WHERE id=?", java.sql.Timestamp.from(NOW), old.id());
            assertThat(expiry.runOnce()).isEqualTo(1);
        } else if (operation.equals("release")) {
            post("/holds/" + old.id() + "/release", Map.of("userId", "user"));
        } else {
            doReturn(new PgClient.Approval(false, "DECLINED")).when(pg).confirm(anyString(), any(UUID.class), anyInt());
            error(confirm(old, old.amount()), 402, "PAYMENT_DECLINED");
        }
        assertOwned(newer, List.of(11, 12), "HELD", false);
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=1", String.class)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_seats WHERE reservation_id=? AND released_at IS NOT NULL",
                Long.class, old.id())).isEqualTo(2);
        assertInvariants();
    }

    @Test void approvalCannotCommitPartialSaleWhenOneOwnershipCheckFails() throws Exception {
        var order = order();
        jdbc.update("UPDATE seats SET current_reservation_id=NULL WHERE id=11");
        assertThatThrownBy(() -> confirm(order, order.amount())).hasRootCauseInstanceOf(IllegalStateException.class);
        assertReservation(order.id(), "CONFIRMING");
        assertThat(jdbc.queryForList("SELECT status FROM seats WHERE id IN (1,11) ORDER BY id", String.class)).containsOnly("HELD");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, order.orderId())).isEqualTo("REQUESTED");
        jdbc.update("UPDATE seats SET current_reservation_id=? WHERE id=11", order.id());
        when(pg.status("card")).thenReturn(PgClient.Status.DONE);
        clock.advance(Duration.ofSeconds(30));
        assertThat(recovery.runOnce()).isEqualTo(1);
        assertOwned(order.id(), List.of(1, 11), "SOLD", false);
        assertInvariants();
    }

    @Test void resetClearsNewTablesAndTogglesOnlyNewSeatBackstop() throws Exception {
        hold("user", List.of(1, 2), "hold");
        jdbc.update("INSERT INTO release_batches(release_at,seat_count) VALUES (?,2)", java.sql.Timestamp.from(NOW));
        configure(Map.of("dbBackstop", false));
        for (String table : List.of("reservations", "reservation_seats", "release_batches")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN "
                + "('ux_res_seat_active','ux_rs_seat_active','ux_res_user_active')", Long.class)).isZero();
        configure(Map.of("dbBackstop", true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN "
                + "('ux_rs_seat_active','ux_res_user_active')", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='ux_res_seat_active'", Long.class)).isZero();
    }

    @Test void naiveWithoutBackstopReportsObservedOverselling() throws Exception {
        configure(Map.of("strategy", "naive", "dbBackstop", false));
        var results = concurrent(200, i -> holdUnchecked("u-" + i, i % 2 == 0 ? List.of(1, 2) : List.of(2, 1), "k-" + i));
        System.out.println("V2 naive backstop=false: " + results.stream().filter(r -> r.getResponse().getStatus() == 201).count()
                + " successful pair holds / 200; active memberships="
                + jdbc.queryForObject("SELECT count(*) FROM reservation_seats WHERE released_at IS NULL", Long.class));
    }

    private void configure(Map<String, Object> changes) throws Exception {
        var settings = new LinkedHashMap<String, Object>(Map.of("strategy", "conditional", "dbBackstop", true,
                "timeScale", 1, "saleDurationSec", 1200, "holdTtlSec", 180,
                "confirmDeadlineSec", 30, "maxSeatsPerUser", 4));
        settings.put("rows", 10);
        settings.put("cols", 10);
        settings.put("grades", List.of(Map.of("name", "VIP", "rows", 1, "price", 150000),
                Map.of("name", "S", "rows", 2, "price", 120000), Map.of("name", "A", "rows", 3, "price", 90000),
                Map.of("name", "B", "rows", 4, "price", 60000)));
        settings.putAll(changes);
        assertThat(post("/admin/reset", settings).getResponse().getStatus()).isEqualTo(200);
    }
    private MvcResult hold(String user, List<Integer> ids, String key) throws Exception {
        return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/holds").header("Idempotency-Key", key), Map.of("userId", user, "seatIds", ids));
    }
    private MvcResult holdUnchecked(String user, List<Integer> ids, String key) {
        try { return hold(user, ids, key); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private MvcResult post(String path, Object body) throws Exception { return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path), body); }
    private MvcResult send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn();
    }
    private JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }
    private void error(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        assertThat(body(r).get("code").asText()).isEqualTo(code);
    }
    private List<MvcResult> concurrent(int n, IntFunction<MvcResult> request) throws Exception {
        var ready = new CountDownLatch(n);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(n)) {
            var futures = new ArrayList<java.util.concurrent.Future<MvcResult>>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(executor.submit(() -> { ready.countDown(); start.await(); return request.apply(index); }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<MvcResult>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }
    private void assertOwned(long id, List<Integer> ids, String status, boolean released) {
        for (int seatId : ids) {
            assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=?", String.class, seatId)).isEqualTo(status);
            assertThat(jdbc.queryForObject("SELECT current_reservation_id FROM seats WHERE id=?", Long.class, seatId))
                    .isEqualTo(released ? null : id);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_seats WHERE reservation_id=? AND released_at IS "
                + (released ? "NOT NULL" : "NULL"), Long.class, id)).isEqualTo(ids.size());
    }
    private void assertInvariants() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reservations r WHERE
                  r.seat_count<>(SELECT count(*) FROM reservation_seats rs WHERE rs.reservation_id=r.id) OR
                  r.seat_id<>(SELECT min(seat_id) FROM reservation_seats rs WHERE rs.reservation_id=r.id)
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT seat_id FROM reservation_seats WHERE released_at IS NULL
                  GROUP BY seat_id HAVING count(*)>1) duplicates
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reservation_seats rs JOIN reservations r ON r.id=rs.reservation_id
                JOIN seats s ON s.id=rs.seat_id WHERE rs.released_at IS NULL AND
                  (s.current_reservation_id IS DISTINCT FROM r.id OR
                    (r.status IN ('HELD','CONFIRMING') AND s.status<>'HELD') OR
                    (r.status='CONFIRMED' AND s.status<>'SOLD'))
                """, Long.class)).isZero();
    }
    private void assertReservation(long id, String status) {
        assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, id)).isEqualTo(status);
    }
    private Order order() throws Exception {
        long id = body(hold("user", List.of(11, 1), "order")).get("reservationId").asLong();
        var checkout = body(post("/holds/" + id + "/checkout", Map.of("userId", "user")));
        return new Order(id, UUID.fromString(checkout.get("orderId").asText()), checkout.get("amount").asInt());
    }
    private MvcResult confirm(Order order, int amount) throws Exception {
        return post("/payments/confirm", Map.of("userId", "user", "orderId", order.orderId(), "amount", amount, "paymentKey", "card"));
    }
    private record Order(long id, UUID orderId, int amount) {}
}
