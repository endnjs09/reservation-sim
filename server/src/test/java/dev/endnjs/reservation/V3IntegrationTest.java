package dev.endnjs.reservation;

import com.zaxxer.hikari.HikariDataSource;
import dev.endnjs.reservation.admin.AdminService;
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
class V3IntegrationTest {
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
    @Autowired ExpiryScheduler expiry;
    @Autowired MetricsCollector counters;
    @Autowired MetricsService metrics;
    @Autowired HikariDataSource datasource;
    @Autowired AdminService admin;
    @Autowired dev.endnjs.reservation.payment.ConfirmRecoveryScheduler recovery;
    @MockitoBean PgClient pg;

    @BeforeEach void reset() throws Exception {
        clock.set(NOW); configure(Map.of());
        when(pg.confirm(anyString(),any(UUID.class),anyInt())).thenReturn(new PgClient.Approval(true,null));
    }

    @ParameterizedTest @ValueSource(ints={1,2,4})
    void depositDeadlineScalesAndPaymentSellsWholeGroupWithoutPg(int scale) throws Exception {
        configure(Map.of("timeScale",scale));
        var held=hold("user",List.of(2,1));
        var requested=body(deposit(held,null));
        assertThat(requested.get("status").asText()).isEqualTo("PENDING_DEPOSIT");
        assertThat(requested.get("amount").asInt()).isEqualTo(200);
        assertThat(Instant.parse(requested.get("depositDeadline").asText())).isEqualTo(NOW.plusSeconds(60/scale));
        assertGroup(held,"PENDING_DEPOSIT",false);
        assertThat(reservation(held).get("paymentMethod").asText()).isEqualTo("DEPOSIT");
        assertThat(reservation(held).get("payment").isNull()).isTrue();
        error(holdResult("user",List.of(3),null),409,"USER_ALREADY_PURCHASED");
        clock.advance(Duration.ofSeconds(60/scale).minusNanos(1_000_000));
        assertThat(pay(held,200).getResponse().getStatus()).isEqualTo(200);
        assertGroup(held,"SOLD",false);
        assertReservation(held,"CONFIRMED");
        assertThat(depositExpiry.runOnce()).isZero();
        assertThat(counters.counters().get("depositsRequested")).isEqualTo(1);
        assertThat(counters.counters().get("depositsPaid")).isEqualTo(1);
        error(pay(held,200),409,"DEPOSIT_NOT_ACCEPTABLE");
        verifyNoInteractions(pg);
        assertInvariants();
    }



    @Test void requestRejectsWrongUserExpiredOrNonHeldReservation() throws Exception {
        var held=hold("user",List.of(1,2));
        error(postJson("/holds/"+held.id()+"/deposit",Map.of("userId","other")),409,"RESERVATION_NOT_PAYABLE");
        assertThat(deposit(held,null).getResponse().getStatus()).isEqualTo(200);
        error(deposit(held,null),409,"RESERVATION_NOT_PAYABLE");
        var expired=hold("expired",List.of(3,4));
        clock.advance(Duration.ofSeconds(180));
        error(deposit(expired,null),409,"RESERVATION_NOT_PAYABLE");
        error(postJson("/holds/999/deposit",Map.of("userId","user")),404,"RESERVATION_NOT_FOUND");
        verifyNoInteractions(pg);
    }

    @Test void paymentValidatesOwnerAmountStateAndExactDeadline() throws Exception {
        var held=hold("user",List.of(1,2));
        error(pay(held,200),409,"DEPOSIT_NOT_ACCEPTABLE");
        deposit(held,null);
        error(pay(held,201),409,"DEPOSIT_NOT_ACCEPTABLE");
        error(postJson("/deposits/"+held.id()+"/pay",Map.of("userId","other","amount",200)),409,"DEPOSIT_NOT_ACCEPTABLE");
        error(postJson("/deposits/999/pay",Map.of("userId","user","amount",200)),409,"DEPOSIT_NOT_ACCEPTABLE");
        clock.advance(Duration.ofSeconds(60));
        error(pay(held,200),409,"DEPOSIT_NOT_ACCEPTABLE");
        assertThat(depositExpiry.runOnce()).isEqualTo(1);
        assertReservation(held,"DEPOSIT_EXPIRED");
        assertGroup(held,"RETURN_PENDING",true);
        error(pay(held,200),409,"DEPOSIT_NOT_ACCEPTABLE");
        error(cancel(held),409,"RESERVATION_NOT_CANCELABLE");
        assertInvariants();
    }

    @ParameterizedTest @ValueSource(ints={1,4})
    void staggeredExpirationsJoinFirstBatchAndReopenAtItsDeadline(int scale) throws Exception {
        configure(Map.of("timeScale",scale,"depositDeadlineSec",64,"returnDelaySec",32));
        var first=hold("first",List.of(1,2)); deposit(first,null);
        clock.advance(Duration.ofSeconds(8/scale));
        var second=hold("second",List.of(3,4)); deposit(second,null);
        clock.set(NOW.plusSeconds(64/scale).minusMillis(1));
        assertThat(depositExpiry.runOnce()).isZero();
        clock.advance(Duration.ofMillis(1));
        assertThat(depositExpiry.runOnce()).isEqualTo(1);
        assertGroup(first,"RETURN_PENDING",true);
        Instant releaseAt=NOW.plusSeconds(96/scale);
        assertThat(jdbc.queryForObject("SELECT release_at FROM release_batches",Timestamp.class).toInstant()).isEqualTo(releaseAt);
        clock.advance(Duration.ofSeconds(8/scale));
        assertThat(depositExpiry.runOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM release_batches",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT seat_count FROM release_batches",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT release_batch_id) FROM seats WHERE status='RETURN_PENDING'",Long.class)).isEqualTo(1);
        var stats=body(mvc.perform(get("/admin/stats")).andReturn()).get("releaseBatches");
        assertThat(stats.get("open").asInt()).isEqualTo(1);
        assertThat(stats.get("nextReleaseAt").asText()).isEqualTo(releaseAt.toString());
        clock.set(releaseAt.minusMillis(1)); assertThat(reopen.runOnce()).isZero();
        clock.advance(Duration.ofMillis(1)); assertThat(reopen.runOnce()).isEqualTo(4);
        assertGroup(first,"AVAILABLE",true); assertGroup(second,"AVAILABLE",true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE release_batch_id IS NOT NULL",Long.class)).isZero();
        assertThat(reopen.runOnce()).isZero();
        assertThat(depositExpiry.runOnce()).isZero();
        assertThat(counters.counters().get("depositsExpired")).isEqualTo(2);
        assertThat(counters.counters().get("reopenCount")).isEqualTo(1);
        assertThat(counters.counters().get("reopenSeats")).isEqualTo(4);
        assertThat(counters.counters().get("immediateReturns")).isZero();
        assertInvariants();
        assertThat(holdResult("first",List.of(1,2),null).getResponse().getStatus()).isEqualTo(201);
    }

    @Test void simultaneousExpiryAndReopenCallsDoNotDuplicateBatchesOrCounters() throws Exception {
        var first=hold("first",List.of(1,2)); deposit(first,null);
        var second=hold("second",List.of(3,4)); deposit(second,null);
        clock.advance(Duration.ofSeconds(60));
        var expired=concurrent(12,i -> depositExpiry.runOnce());
        assertThat(expired.stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM release_batches WHERE NOT released",Long.class)).isEqualTo(1);
        clock.advance(Duration.ofSeconds(30));
        var reopened=concurrent(12,i -> reopen.runOnce());
        assertThat(reopened.stream().mapToInt(Integer::intValue).sum()).isEqualTo(4);
        assertThat(counters.counters().get("reopenCount")).isEqualTo(1);
        assertThat(counters.counters().get("reopenSeats")).isEqualTo(4);
        assertInvariants();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void depositsCancelImmediatelyBeforeOrAfterPaymentAndPermitNewPurchase(boolean paid) throws Exception {
        var held=hold("user",List.of(1,2)); deposit(held,null);
        if (paid) pay(held,200);
        assertThat(cancel(held).getResponse().getStatus()).isEqualTo(200);
        assertReservation(held,"CANCELED"); assertGroup(held,"AVAILABLE",true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM release_batches",Long.class)).isZero();
        assertThat(counters.counters().get("userCancels")).isEqualTo(1);
        assertThat(counters.counters().get("immediateReturns")).isEqualTo(2);
        error(cancel(held),409,"RESERVATION_NOT_CANCELABLE");
        error(pay(held,200),409,"DEPOSIT_NOT_ACCEPTABLE");
        assertThat(holdResult("user",List.of(1,2),null).getResponse().getStatus()).isEqualTo(201);
        verifyNoInteractions(pg);
        assertInvariants();
    }

    @Test void cardCancellationContactsPgThenAtomicallyReleasesWholeGroup() throws Exception {
        var card=card("user",List.of(1,2));
        assertThat(cancel(card.held()).getResponse().getStatus()).isEqualTo(200);
        var inOrder=inOrder(pg); inOrder.verify(pg).confirm("card-user",card.orderId(),200); inOrder.verify(pg).cancel("card-user");
        assertReservation(card.held(),"CANCELED"); assertGroup(card.held(),"AVAILABLE",true);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,card.orderId())).isEqualTo("CANCELED");
        assertThat(counters.counters().get("userCancels")).isEqualTo(1);
        assertThat(counters.counters().get("immediateReturns")).isEqualTo(2);
        assertInvariants();
    }

    @Test void failedPgCancellationReturns502AndKeepsAllStatesUntilRetry() throws Exception {
        var card=card("user",List.of(1,2));
        doThrow(new PgUnavailableException("cancel failed")).when(pg).cancel("card-user");
        error(cancel(card.held()),502,"PG_UNAVAILABLE");
        assertGroup(card.held(),"SOLD",false); assertReservation(card.held(),"CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,card.orderId())).isEqualTo("APPROVED");
        assertThat(counters.counters().get("userCancels")).isZero();
        assertThat(counters.counters().get("immediateReturns")).isZero();
        doNothing().when(pg).cancel("card-user");
        assertThat(cancel(card.held()).getResponse().getStatus()).isEqualTo(200);
        verify(pg,times(2)).cancel("card-user");
        assertInvariants();
    }

    @Test void cancellationValidatesUserAndStatesWithoutCallingPg() throws Exception {
        var held=hold("user",List.of(1,2));
        error(cancel(held),409,"RESERVATION_NOT_CANCELABLE");
        error(postJson("/reservations/999/cancel",Map.of("userId","user")),409,"RESERVATION_NOT_CANCELABLE");
        deposit(held,null);
        error(postJson("/reservations/"+held.id()+"/cancel",Map.of("userId","other")),409,"RESERVATION_NOT_CANCELABLE");
        assertGroup(held,"PENDING_DEPOSIT",false);
        verifyNoInteractions(pg);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void concurrentCancellationHasOneWinnerAndNoDuplicatePgOrCounters(boolean cardMode) throws Exception {
        Held held=cardMode ? card("user",List.of(1,2)).held() : hold("user",List.of(1,2));
        if (!cardMode) deposit(held,null);
        var results=concurrent(40,i -> unchecked(() -> cancel(held)));
        assertThat(results.stream().filter(r -> r.getResponse().getStatus()==200).count()).isEqualTo(1);
        for (var r:results) if (r.getResponse().getStatus()!=200) error(r,409,"RESERVATION_NOT_CANCELABLE");
        if (cardMode) verify(pg).cancel("card-user"); else verifyNoInteractions(pg);
        assertGroup(held,"AVAILABLE",true);
        assertThat(counters.counters().get("userCancels")).isEqualTo(1);
        assertThat(counters.counters().get("immediateReturns")).isEqualTo(2);
        assertInvariants();
    }

    @Test void simultaneousPaymentsHaveOneWinner() throws Exception {
        var held=hold("user",List.of(1,2)); deposit(held,null);
        var results=concurrent(40,i -> unchecked(() -> pay(held,200)));
        assertThat(results.stream().filter(r -> r.getResponse().getStatus()==200).count()).isEqualTo(1);
        for (var r:results) if (r.getResponse().getStatus()!=200) error(r,409,"DEPOSIT_NOT_ACCEPTABLE");
        assertGroup(held,"SOLD",false);
        assertThat(counters.counters().get("depositsPaid")).isEqualTo(1);
        assertInvariants();
    }

    @Test void paymentRacingCancellationAlwaysEndsWithWholeGroupCanceled() throws Exception {
        for (int attempt=0;attempt<10;attempt++) {
            configure(Map.of()); var held=hold("user",List.of(1,2)); deposit(held,null);
            var results=concurrent(2,i -> unchecked(() -> i==0 ? pay(held,200) : cancel(held)));
            assertThat(results.get(1).getResponse().getStatus()).isEqualTo(200);
            assertThat(results.get(0).getResponse().getStatus()).isIn(200,409);
            assertReservation(held,"CANCELED"); assertGroup(held,"AVAILABLE",true); assertInvariants();
            assertThat(counters.counters().get("userCancels")).isEqualTo(1);
            assertThat(counters.counters().get("immediateReturns")).isEqualTo(2);
        }
    }

    @Test void expiryRacingCancellationPreservesOneFinalWholeGroupState() throws Exception {
        for (int attempt=0;attempt<10;attempt++) {
            configure(Map.of()); var held=hold("user",List.of(1,2)); deposit(held,null); clock.advance(Duration.ofSeconds(60));
            var results=concurrent(2,i -> i==0 ? depositExpiry.runOnce() : unchecked(() -> cancel(held)).getResponse().getStatus());
            String status=reservation(held).get("status").asText();
            if (status.equals("CANCELED")) { assertGroup(held,"AVAILABLE",true); assertThat(results.get(1)).isEqualTo(200); }
            else { assertThat(status).isEqualTo("DEPOSIT_EXPIRED"); assertGroup(held,"RETURN_PENDING",true); assertThat(results.get(1)).isEqualTo(409); }
            assertInvariants();
        }
    }

    @Test void cardCancellationHoldsNoDbConnectionAndCanFinishAfterSaleEnd() throws Exception {
        configure(Map.of("saleDurationSec",5)); var card=card("user",List.of(1,2));
        var entered=new CountDownLatch(1); var proceed=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(proceed.await(10,TimeUnit.SECONDS)).isTrue(); return null; }).when(pg).cancel("card-user");
        try (var executor=Executors.newSingleThreadExecutor()) {
            var future=executor.submit(() -> cancel(card.held()));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(datasource.getHikariPoolMXBean().getActiveConnections()).isZero();
                assertReservation(card.held(),"CONFIRMED"); clock.advance(Duration.ofSeconds(5)); saleEnd.runOnce();
            } finally { proceed.countDown(); }
            assertThat(future.get(5,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        assertGroup(card.held(),"AVAILABLE",true); assertInvariants();
    }

    @Test void lateCardCancellationCannotModifyReusedReservationIdAfterReset() throws Exception {
        var card=card("user",List.of(1,2)); var entered=new CountDownLatch(1); var proceed=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(proceed.await(10,TimeUnit.SECONDS)).isTrue(); return null; }).when(pg).cancel("card-user");
        try (var executor=Executors.newSingleThreadExecutor()) {
            var future=executor.submit(() -> cancel(card.held()));
            Held next;
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); configure(Map.of());
                next=hold("user",List.of(1,2)); assertThat(next.id()).isEqualTo(card.held().id());
            } finally { proceed.countDown(); }
            error(future.get(5,TimeUnit.SECONDS),409,"RESERVATION_NOT_CANCELABLE");
            assertGroup(next,"HELD",false); assertReservation(next,"HELD");
        }
        assertThat(counters.counters().get("userCancels")).isZero(); assertInvariants();
    }

    @Test void sellingEndCleansOpenBatchWithoutReopenAndKeepsLaterDepositPayable() throws Exception {
        configure(Map.of("saleDurationSec",70));
        var first=hold("first",List.of(1,2)); deposit(first,null);
        clock.advance(Duration.ofSeconds(20)); var later=hold("later",List.of(3,4)); deposit(later,null);
        var unconfirmed=hold("held",List.of(5,6));
        clock.set(NOW.plusSeconds(60)); depositExpiry.runOnce();
        assertGroup(first,"RETURN_PENDING",true);
        clock.set(NOW.plusSeconds(70)); saleEnd.runOnce();
        assertGroup(first,"AVAILABLE",true); assertGroup(unconfirmed,"AVAILABLE",true); assertReservation(unconfirmed,"EXPIRED");
        assertGroup(later,"PENDING_DEPOSIT",false);
        assertThat(pay(later,200).getResponse().getStatus()).isEqualTo(200);
        assertThat(counters.counters().get("reopenCount")).isZero();
        assertThat(counters.counters().get("reopenSeats")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM release_batches WHERE NOT released",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM release_batches WHERE reopened",Long.class)).isZero();
        assertThat(reopen.runOnce()).isZero(); assertInvariants();
    }

    /** v0.5 4.5: the shared saleEndAt closes /seats and new holds; existing reservations finish by v0.4 4.4. */
    @Test void saleEndClosesSeatsAndNewHoldsWhileExistingReservationsFinish() throws Exception {
        configure(Map.of("saleDurationSec",30));
        doThrow(new PgUnavailableException("unknown")).when(pg).confirm(anyString(),any(UUID.class),anyInt());
        doThrow(new PgUnavailableException("unknown")).when(pg).status(anyString());
        var card=hold("card",List.of(1,2)); var checkout=body(postJson("/holds/"+card.id()+"/checkout",Map.of("userId","card")));
        assertThat(postJson("/payments/confirm",Map.of("userId","card","orderId",checkout.get("orderId").asText(),"amount",card.amount(),
                "paymentKey","card-key")).getResponse().getStatus()).isEqualTo(202);
        var bank=hold("bank",List.of(3,4)); deposit(bank,null);

        var open=mvc.perform(get("/seats")).andReturn(); assertThat(open.getResponse().getStatus()).isEqualTo(200);
        var seats=body(open);
        assertSameShape(seats,json.readTree(java.nio.file.Path.of("../contracts/seats-response.json").toFile()));
        assertThat(seats.get("availableSeats").asLong()).isEqualTo(4); assertThat(seats.get("heldSeats").asLong()).isEqualTo(2);
        assertThat(seats.get("pendingDepositSeats").asLong()).isEqualTo(2); assertThat(seats.get("returnPendingSeats").asLong()).isZero();
        assertThat(seats.get("soldOut").asBoolean()).isFalse(); assertThat(seats.get("releaseAt").isNull()).isTrue();
        assertThat(seats.get("saleEndAt").asText()).isEqualTo(NOW.plusSeconds(30).toString());
        assertThat(seats.get("phase").asText()).isNotEqualTo("ENDED");

        clock.set(NOW.plusSeconds(30)); // Before the sale-end scheduler runs: the time alone closes both entry points.
        error(mvc.perform(get("/seats")).andReturn(),409,"SALE_ENDED");
        error(holdResult("late",List.of(5),null),409,"SALE_ENDED");
        saleEnd.runOnce();
        error(mvc.perform(get("/seats")).andReturn(),409,"SALE_ENDED");
        assertReservation(card,"CONFIRMING");
        assertThat(pay(bank,bank.amount()).getResponse().getStatus()).isEqualTo(200); assertReservation(bank,"CONFIRMED");
        assertThat(cancel(bank).getResponse().getStatus()).isEqualTo(200); assertReservation(bank,"CANCELED");
        doReturn(PgClient.Status.DONE).when(pg).status(anyString());
        clock.set(NOW.plusSeconds(90)); assertThat(recovery.runOnce()).isEqualTo(1); assertReservation(card,"CONFIRMED");
        assertInvariants();
    }
    private static void assertSameShape(JsonNode actual,JsonNode contract) {
        assertThat(actual.propertyNames()).as("/seats top-level fields").containsExactlyInAnyOrderElementsOf(contract.propertyNames());
        for (String array : List.of("seats","grades"))
            assertThat(actual.get(array).get(0).propertyNames()).as(array+"[] fields").containsExactlyInAnyOrderElementsOf(contract.get(array).get(0).propertyNames());
    }

    @Test void depositsExpiringAfterSaleEndReturnWithoutCreatingOrReopeningABatch() throws Exception {
        configure(Map.of("saleDurationSec",30)); var held=hold("user",List.of(1,2)); deposit(held,null);
        clock.advance(Duration.ofSeconds(30)); saleEnd.runOnce(); assertGroup(held,"PENDING_DEPOSIT",false);
        clock.advance(Duration.ofSeconds(30)); assertThat(depositExpiry.runOnce()).isEqualTo(1);
        assertReservation(held,"DEPOSIT_EXPIRED"); assertGroup(held,"AVAILABLE",true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM release_batches",Long.class)).isZero();
        assertThat(counters.counters().get("reopenCount")).isZero(); assertThat(reopen.runOnce()).isZero();
        assertThat(metrics.runOnce().phase().name()).isEqualTo("ENDED"); assertInvariants();
    }

    @Test void reopenSchedulerAloneAtSaleEndCleansBatchWithoutPublishingReopen() throws Exception {
        configure(Map.of("saleDurationSec",70)); var held=hold("user",List.of(1,2)); deposit(held,null);
        clock.advance(Duration.ofSeconds(60)); depositExpiry.runOnce(); clock.advance(Duration.ofSeconds(10));
        assertThat(reopen.runOnce()).isZero(); assertGroup(held,"AVAILABLE",true);
        assertThat(counters.counters().get("reopenCount")).isZero(); assertInvariants();
    }

    @Test void fiveStateMetricsEventsReopenWindowAndRestartHistoryReflectDb() throws Exception {
        configure(Map.of("cols",4,"timeScale",4,"depositDeadlineSec",8,"returnDelaySec",8,"reopenWindowSec",8));
        var first=hold("first",List.of(1,2)); assertThat(metrics.runOnce().phase().name()).isEqualTo("RUSH");
        var second=hold("second",List.of(3,4)); deposit(first,null); deposit(second,null);
        var sample=metrics.runOnce(); assertThat(sample.seatMap()).isEqualTo("DDDD");
        assertThat(sample.phase().name()).isEqualTo("SOLD_OUT");
        assertThat(sample.depositRemainingMs().values()).containsOnly(2000L); assertThat(sample.pendingDepositSeats()).isEqualTo(4);
        clock.advance(Duration.ofSeconds(2)); depositExpiry.runOnce(); sample=metrics.runOnce();
        assertThat(sample.seatMap()).isEqualTo("RRRR"); assertThat(sample.returnPendingSeats()).isEqualTo(4);
        assertThat(sample.releaseAt()).isEqualTo(NOW.plusSeconds(4)); assertThat(sample.events().get("DEPOSIT_EXPIRED")).isEqualTo(2);
        assertThat(sample.schedulers().get("depositExpiry").get("expired")).isEqualTo(2);
        assertThat(sample.depositRemainingMs()).isEmpty(); clock.advance(Duration.ofSeconds(2)); reopen.runOnce();
        sample=metrics.runOnce(); assertThat(sample.phase().name()).isEqualTo("REOPEN"); assertThat(sample.lastReopenAt()).isEqualTo(NOW.plusSeconds(4));
        assertThat(sample.events().get("REOPEN")).isEqualTo(1); assertThat(sample.schedulers().get("reopen").get("reopened")).isEqualTo(4);
        admin.run(null); sample=metrics.runOnce(); assertThat(sample.phase().name()).isEqualTo("REOPEN");
        assertThat(sample.releaseAt()).isNull(); clock.advance(Duration.ofMillis(1999)); assertThat(metrics.runOnce().phase().name()).isEqualTo("REOPEN");
        clock.advance(Duration.ofMillis(1)); assertThat(metrics.runOnce().phase().name()).isEqualTo("RESALE");
        configure(Map.of()); assertThat(metrics.runOnce().phase().name()).isEqualTo("OPEN");
        assertThat(metrics.snapshot().lastReopenAt()).isNull(); assertThat(counters.counters().values()).containsOnly(0L);
    }

    @Test void reopeningNeverOverridesRushWhenAvailabilityWasNeverZero() throws Exception {
        var held=hold("user",List.of(1,2)); deposit(held,null); clock.advance(Duration.ofSeconds(60)); depositExpiry.runOnce();
        clock.advance(Duration.ofSeconds(30)); reopen.runOnce(); assertThat(metrics.runOnce().phase().name()).isEqualTo("RUSH");
        admin.run(null); assertThat(metrics.runOnce().phase().name()).isEqualTo("RUSH");
    }

    @ParameterizedTest @ValueSource(strings={"release","expiry","decline","saleEnd"})
    void immediateReturnCountsActualSeatsAndNotRepeatedReservationCalls(String cause) throws Exception {
        var held=hold("user",List.of(1,2));
        if (cause.equals("release")) { postJson("/holds/"+held.id()+"/release",Map.of("userId","user")); postJson("/holds/"+held.id()+"/release",Map.of("userId","user")); }
        else if (cause.equals("expiry")) { clock.advance(Duration.ofSeconds(180)); expiry.runOnce(); expiry.runOnce(); }
        else if (cause.equals("saleEnd")) { clock.advance(Duration.ofSeconds(1200)); saleEnd.runOnce(); saleEnd.runOnce(); }
        else {
            var checkout=body(postJson("/holds/"+held.id()+"/checkout",Map.of("userId","user")));
            doReturn(new PgClient.Approval(false,"DECLINED")).when(pg).confirm(anyString(),any(UUID.class),anyInt());
            error(postJson("/payments/confirm",Map.of("userId","user","orderId",checkout.get("orderId").asText(),"amount",200,"paymentKey","decline")),402,"PAYMENT_DECLINED");
        }
        assertThat(counters.counters().get("immediateReturns")).isEqualTo(2); assertInvariants();
    }

    @Test void zeroPriceDepositAndValidationErrorsKeepExistingAmountContract() throws Exception {
        configure(Map.of("grades",List.of(Map.of("name","VIP","rows",1,"price",0))));
        var held=hold("free",List.of(1,2)); deposit(held,null); assertThat(pay(held,0).getResponse().getStatus()).isEqualTo(200);
        error(postJson("/deposits/"+held.id()+"/pay",Map.of("userId","free","amount",-1)),400,"VALIDATION_FAILED");
        error(postJson("/deposits/"+held.id()+"/pay",Map.of("userId","free")),400,"VALIDATION_FAILED");
        for (String key:List.of("depositDeadlineSec","returnDelaySec","reopenWindowSec")) error(postJson("/admin/reset",Map.of(key,0)),400,"VALIDATION_FAILED");
        assertInvariants();
    }

    @ParameterizedTest @ValueSource(strings={"request","pay"})
    void depositTransitionsRollBackIfAnySeatOwnershipCheckFails(String action) throws Exception {
        var held=hold("user",List.of(1,2)); if (action.equals("pay")) deposit(held,null);
        jdbc.update("UPDATE seats SET current_reservation_id=NULL WHERE id=2");
        assertThatThrownBy(() -> { if (action.equals("pay")) pay(held,200); else deposit(held,null); })
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertGroup(held,action.equals("pay") ? "PENDING_DEPOSIT" : "HELD",false);
        assertThat(counters.counters().get("depositsPaid")).isZero();
        if (action.equals("request")) assertThat(counters.counters().get("depositsRequested")).isZero();
        jdbc.update("UPDATE seats SET current_reservation_id=? WHERE id=2",held.id());
        if (action.equals("request")) deposit(held,null);
        assertThat(pay(held,200).getResponse().getStatus()).isEqualTo(200); assertInvariants();
    }

    @Test void expiryAndReopenNeverOverwriteNewSeatOwners() throws Exception {
        configure(Map.of("dbBackstop",false)); var old=hold("old",List.of(1,2)); deposit(old,null);
        jdbc.update("UPDATE seats SET status='AVAILABLE',current_reservation_id=NULL WHERE id=2");
        var newer=hold("newer",List.of(2,3)); clock.advance(Duration.ofSeconds(60));
        assertThat(depositExpiry.runOnce()).isEqualTo(1);
        assertGroup(newer,"HELD",false);
        assertThat(jdbc.queryForObject("SELECT seat_count FROM release_batches",Integer.class)).isEqualTo(1);
        clock.advance(Duration.ofSeconds(30)); assertThat(reopen.runOnce()).isEqualTo(1);
        assertGroup(newer,"HELD",false);
        assertThat(jdbc.queryForObject("SELECT current_reservation_id FROM seats WHERE id=2",Long.class)).isEqualTo(newer.id());
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=1",String.class)).isEqualTo("AVAILABLE"); assertInvariants();
    }

    @Test void cancelAndExpiryEventsAreRollingOneSecondCommittedCounts() throws Exception {
        var first=hold("first",List.of(1,2)); deposit(first,null);
        var second=hold("second",List.of(3,4)); deposit(second,null);
        cancel(first); assertThat(metrics.runOnce().events().get("USER_CANCEL")).isEqualTo(1);
        clock.advance(Duration.ofMillis(999)); assertThat(metrics.runOnce().events().get("USER_CANCEL")).isEqualTo(1);
        clock.advance(Duration.ofMillis(1)); assertThat(metrics.runOnce().events().get("USER_CANCEL")).isZero();
        clock.set(NOW.plusSeconds(60)); depositExpiry.runOnce();
        assertThat(metrics.runOnce().events().get("DEPOSIT_EXPIRED")).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1)); assertThat(metrics.runOnce().events().get("DEPOSIT_EXPIRED")).isZero();
    }

    private void configure(Map<String,Object> changes) throws Exception {
        var settings=new LinkedHashMap<String,Object>(Map.ofEntries(Map.entry("rows",1),Map.entry("cols",8),
                Map.entry("grades",List.of(Map.of("name","VIP","rows",1,"price",100))),Map.entry("strategy","conditional"),
                Map.entry("dbBackstop",true),Map.entry("timeScale",1),Map.entry("saleDurationSec",1200),
                Map.entry("holdTtlSec",180),Map.entry("depositDeadlineSec",60),Map.entry("returnDelaySec",30),Map.entry("reopenWindowSec",50),
                Map.entry("maxSeatsPerUser",4)));
        settings.putAll(changes); assertThat(postJson("/admin/reset",settings).getResponse().getStatus()).isEqualTo(200);
    }
    private Held hold(String user,List<Integer> ids) throws Exception { return hold(user,ids,null); }
    private Held hold(String user,List<Integer> ids,String token) throws Exception {
        var result=holdResult(user,ids,token); assertThat(result.getResponse().getStatus()).isEqualTo(201);
        var b=body(result); return new Held(b.get("reservationId").asLong(),user,ids,b.get("totalPrice").asInt());
    }
    private MvcResult holdResult(String user,List<Integer> ids,String token) throws Exception {
        var request=post("/holds").header("Idempotency-Key",UUID.randomUUID().toString()); if (token!=null) request.header("X-Admission-Key",token);
        return send(request,Map.of("userId",user,"seatIds",ids));
    }
    private MvcResult deposit(Held held,String token) throws Exception {
        var request=post("/holds/"+held.id()+"/deposit"); if (token!=null) request.header("X-Admission-Key",token);
        return send(request,Map.of("userId",held.user()));
    }
    private MvcResult pay(Held held,int amount) throws Exception { return postJson("/deposits/"+held.id()+"/pay",Map.of("userId",held.user(),"amount",amount)); }
    private MvcResult cancel(Held held) throws Exception { return postJson("/reservations/"+held.id()+"/cancel",Map.of("userId",held.user())); }
    private Card card(String user,List<Integer> ids) throws Exception {
        var held=hold(user,ids); var checkout=body(postJson("/holds/"+held.id()+"/checkout",Map.of("userId",user)));
        UUID order=UUID.fromString(checkout.get("orderId").asText());
        assertThat(postJson("/payments/confirm",Map.of("userId",user,"orderId",order,"amount",held.amount(),"paymentKey","card-"+user)).getResponse().getStatus()).isEqualTo(200);
        return new Card(held,order);
    }
    private MvcResult postJson(String path,Object body) throws Exception { return send(post(path),body); }
    private MvcResult send(MockHttpServletRequestBuilder request,Object body) throws Exception { return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn(); }
    private JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }
    private JsonNode reservation(Held held) throws Exception { return body(mvc.perform(get("/reservations/"+held.id())).andReturn()); }
    private void error(MvcResult r,int status,String code) throws Exception { assertThat(r.getResponse().getStatus()).isEqualTo(status); assertThat(body(r).get("code").asText()).isEqualTo(code); }
    private void assertReservation(Held held,String status) throws Exception { assertThat(reservation(held).get("status").asText()).isEqualTo(status); }
    private void assertGroup(Held held,String status,boolean released) {
        for (int id:held.ids()) assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=?",String.class,id)).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_seats WHERE reservation_id=? AND released_at IS "+(released ? "NOT NULL":"NULL"),Long.class,held.id())).isEqualTo(held.ids().size());
    }
    private void assertInvariants() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT seat_id FROM reservation_seats WHERE released_at IS NULL GROUP BY seat_id HAVING count(*)>1) duplicate
                """,Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM seats s LEFT JOIN reservations r ON r.id=s.current_reservation_id
                LEFT JOIN release_batches b ON b.id=s.release_batch_id WHERE
                  (s.status='SOLD' AND (r.status IS DISTINCT FROM 'CONFIRMED' OR NOT EXISTS(SELECT 1 FROM reservation_seats rs WHERE rs.seat_id=s.id AND rs.reservation_id=r.id AND rs.released_at IS NULL))) OR
                  (s.status='HELD' AND (r.status IS NULL OR r.status NOT IN ('HELD','CONFIRMING'))) OR
                  (s.status='PENDING_DEPOSIT' AND r.status IS DISTINCT FROM 'PENDING_DEPOSIT') OR
                  (s.status='RETURN_PENDING' AND (b.id IS NULL OR b.released)) OR
                  (s.status='AVAILABLE' AND (s.current_reservation_id IS NOT NULL OR s.release_batch_id IS NOT NULL))
                """,Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reservations r WHERE r.seat_count<>(SELECT count(*) FROM reservation_seats rs WHERE rs.reservation_id=r.id) OR
                  (r.status IN ('HELD','CONFIRMING','PENDING_DEPOSIT','CONFIRMED') AND
                    (SELECT count(DISTINCT s.status) FROM reservation_seats rs JOIN seats s ON s.id=rs.seat_id WHERE rs.reservation_id=r.id)<>1)
                """,Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT user_id FROM reservations WHERE status IN ('CONFIRMED','PENDING_DEPOSIT') GROUP BY user_id HAVING count(*)>1 OR sum(seat_count)>4) duplicate
                """,Long.class)).isZero();
    }
    private <T> List<T> concurrent(int n,IntFunction<T> action) throws Exception {
        var ready=new CountDownLatch(n); var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(n)) {
            var futures=new ArrayList<java.util.concurrent.Future<T>>();
            for (int i=0;i<n;i++) { int index=i; futures.add(executor.submit(() -> { ready.countDown(); start.await(); return action.apply(index); })); }
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            var results=new ArrayList<T>(); for (var future:futures) results.add(future.get(20,TimeUnit.SECONDS)); return results;
        }
    }
    private interface Request { MvcResult run() throws Exception; }
    private MvcResult unchecked(Request request) { try { return request.run(); } catch (Exception e) { throw new RuntimeException(e); } }
    private record Held(long id,String user,List<Integer> ids,int amount) {}
    private record Card(Held held,UUID orderId) {}
}
