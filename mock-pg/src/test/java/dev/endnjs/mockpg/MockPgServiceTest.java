package dev.endnjs.mockpg;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPgServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private MockPgService pg;
    private List<Long> delays;
    @BeforeEach
    void setup() {
        delays = new ArrayList<>();
        pg = new MockPgService(clock, delays::add);
        pg.configure(new PgConfig(0, 0, 0, 0, 0, 42L));
    }

    @Test void confirmLatencyMeasuresActualWaitAndRetainsCumulativePercentiles() {
        var real=new MockPgService(clock,Thread::sleep);real.configure(new PgConfig(0,0,0,25,25,42L));
        assertThat(real.stats().confirmLatency().p95()).isNull();
        UUID order=UUID.randomUUID();String key=real.auth(order,100);real.confirm(key,order,100);
        assertThat(real.stats().confirmLatency().p95()).isBetween(20.0,300.0);
        assertThat(real.stats().confirmLatency().p95()).isEqualTo(real.stats().confirmLatency().p95());
        real.reset();assertThat(real.stats().confirmLatency().p95()).isNull();
    }

    @Test
    void fixedSeedAuthFailuresMatchExpectedRatioAndAreRepeatable() {
        pg.configure(new PgConfig(0.3, 0, 0, 0, 0, 42L));
        var other = new MockPgService(clock, millis -> {});
        other.configure(pg.config());
        for (int i = 0; i < 10000; i++) {
            UUID order = UUID.randomUUID();
            assertThat(authOutcome(pg, order)).isEqualTo(authOutcome(other, order));
        }
        assertThat(pg.stats().authRequests()).isEqualTo(10000);
        assertThat(pg.stats().authFailed() / 10000.0).isBetween(0.28, 0.32);
    }

    @Test
    void fixedSeedDeclinesMatchExpectedRatioAndAreRepeatable() {
        pg.configure(new PgConfig(0, 0.3, 0, 0, 0, 42L));
        var other = new MockPgService(clock, millis -> {});
        other.configure(pg.config());
        for (int i = 0; i < 10000; i++) {
            UUID order = UUID.randomUUID();
            String first = pg.auth(order, 90000);
            String second = other.auth(order, 90000);
            assertThat(confirmOutcome(pg, first, order)).isEqualTo(confirmOutcome(other, second, order));
        }
        assertThat(pg.stats().confirmRequests()).isEqualTo(10000);
        assertThat(pg.stats().declined() / 10000.0).isBetween(0.28, 0.32);
        assertThat(pg.stats().done() + pg.stats().declined()).isEqualTo(10000);
        assertThat(pg.stats().confirmInflight()).isZero();
    }

    @Test
    void authDoesNotDebitAndApprovalUsesInjectedClock() {
        UUID order = UUID.randomUUID();
        String key = pg.auth(order, 90000);
        assertThat(key).startsWith("pk_");
        assertThat(pg.status(key)).isEqualTo(MockPgService.Status.AUTHORIZED);
        assertThat(pg.stats().done()).isZero();
        var response = pg.confirm(key, order, 90000);
        assertThat(response.status()).isEqualTo(MockPgService.Status.DONE);
        assertThat(response.approvedAt()).isEqualTo(NOW);
        assertThat(pg.payments().getFirst().orderId()).isEqualTo(order);
        assertThat(pg.stats().done()).isEqualTo(1);
        assertThatThrownBy(() -> pg.confirm(key, order, 90000)).isInstanceOf(PgException.class).hasMessage("INVALID_REQUEST");
    }

    @Test
    void mismatchedMissingAndCanceledApprovalAreInvalid() {
        UUID order = UUID.randomUUID();
        String key = pg.auth(order, 90000);
        assertThatThrownBy(() -> pg.confirm("missing", order, 90000)).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(() -> pg.confirm(key, UUID.randomUUID(), 90000)).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(() -> pg.confirm(key, order, 1)).hasMessage("INVALID_REQUEST");
        assertThat(pg.status(key)).isEqualTo(MockPgService.Status.AUTHORIZED);
        pg.cancel(key);
        assertThatThrownBy(() -> pg.confirm(key, order, 90000)).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(() -> pg.status("missing")).isInstanceOf(PgException.class).hasMessage("NOT_FOUND");
    }

    @Test
    void cancelIsIdempotentForAuthorizedDoneAndDeclinedPayments() {
        UUID firstOrder = UUID.randomUUID();
        String authorized = pg.auth(firstOrder, 90000);
        assertThat(pg.cancel(authorized)).isEqualTo(MockPgService.Status.CANCELED);
        assertThat(pg.cancel(authorized)).isEqualTo(MockPgService.Status.CANCELED);
        UUID secondOrder = UUID.randomUUID();
        String done = pg.auth(secondOrder, 90000);
        pg.confirm(done, secondOrder, 90000);
        pg.cancel(done);
        pg.cancel(done);
        assertThat(pg.stats().canceled()).isEqualTo(2);
        assertThat(pg.stats().done()).isZero();
        pg.configure(new PgConfig(0, 1, 0, 0, 0, 42L));
        UUID thirdOrder = UUID.randomUUID();
        String declined = pg.auth(thirdOrder, 90000);
        assertThatThrownBy(() -> pg.confirm(declined, thirdOrder, 90000)).hasMessage("INSUFFICIENT_FUNDS");
        pg.cancel(declined);
        pg.cancel(declined);
        assertThat(pg.stats().canceled()).isEqualTo(2);
        assertThat(pg.status(declined)).isEqualTo(MockPgService.Status.DECLINED);
    }

    @Test
    void normalDelaysAreUniformWithinConfiguredInclusiveBounds() {
        pg.configure(new PgConfig(0, 0, 0, 10, 30, 42L));
        for (int i = 0; i < 1000; i++) {
            UUID order = UUID.randomUUID();
            String key = pg.auth(order, 90000);
            pg.confirm(key, order, 90000);
        }
        assertThat(delays).allSatisfy(ms -> assertThat(ms).isBetween(10L, 30L));
        assertThat(delays).contains(10L, 30L);
        assertThat(delays.stream().mapToLong(Long::longValue).average().orElseThrow()).isBetween(19.0, 21.0);
    }

    @Test
    void timeoutStoresHalfApprovedOutcomesAndInjectsLongResponseDelay() {
        pg.configure(new PgConfig(0, 1, 1, 10, 70, 42L));
        for (int i = 0; i < 3000; i++) {
            UUID order = UUID.randomUUID();
            String key = pg.auth(order, 90000);
            confirmOutcome(pg, key, order);
        }
        assertThat(pg.stats().timedOut()).isEqualTo(3000);
        assertThat(pg.stats().done() / 3000.0).isBetween(0.47, 0.53);
        assertThat(pg.stats().declined()).isZero();
        assertThat(delays).containsOnly(10070L);
    }

    @Test
    void realTimeoutResponseIsDelayedAndCancelDoesNotGetOverwritten() throws Exception {
        var real = new MockPgService(clock, Thread::sleep);
        real.configure(new PgConfig(0, 0, 1, 0, 0, 42L));
        UUID order = UUID.randomUUID();
        String key = real.auth(order, 90000);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> confirmOutcome(real, key, order));
            // A 10-second injected response must not complete in the first 100 milliseconds.
            assertThatThrownBy(() -> request.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(real.stats().confirmInflight()).isEqualTo(1);
            assertThat(real.stats().timedOut()).isEqualTo(1);
            real.cancel(key);
            assertThat(request.get(12, TimeUnit.SECONDS)).isEqualTo("INVALID_REQUEST");
        }
        assertThat(real.status(key)).isEqualTo(MockPgService.Status.CANCELED);
        assertThat(real.stats().done()).isZero();
        assertThat(real.stats().confirmInflight()).isZero();
    }

    @Test
    void ordinaryApprovalCanceledDuringDelayCannotChargeLater() throws Exception {
        var sleeping = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var blocked = new MockPgService(clock, millis -> { sleeping.countDown(); proceed.await(); });
        blocked.configure(new PgConfig(0, 0, 0, 0, 0, 42L));
        UUID order = UUID.randomUUID();
        String key = blocked.auth(order, 90000);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> confirmOutcome(blocked, key, order));
            try {
                assertThat(sleeping.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(blocked.status(key)).isEqualTo(MockPgService.Status.AUTHORIZED);
                blocked.cancel(key);
            } finally { proceed.countDown(); }
            assertThat(request.get(2, TimeUnit.SECONDS)).isEqualTo("INVALID_REQUEST");
        }
        assertThat(blocked.status(key)).isEqualTo(MockPgService.Status.CANCELED);
        assertThat(blocked.stats().done()).isZero();
    }

    @Test
    void resetClearsPaymentsCountersAndPreservesConfig() {
        UUID order = UUID.randomUUID();
        String key = pg.auth(order, 90000);
        pg.confirm(key, order, 90000);
        var config = pg.config();
        pg.reset();
        assertThat(pg.config()).isEqualTo(config);
        assertThat(pg.payments()).isEmpty();
        assertThat(pg.stats()).usingRecursiveComparison().ignoringFields("serverTime").isEqualTo(new MockPgService.Stats(0, 0, 0, 0, 0, 0, 0, 0)); // serverTime: 3장 추가 필드
    }

    private static String authOutcome(MockPgService pg, UUID order) {
        try { pg.auth(order, 90000); return "AUTHORIZED"; }
        catch (PgException failure) { return failure.code(); }
    }
    private static String confirmOutcome(MockPgService pg, String key, UUID order) {
        try { return pg.confirm(key, order, 90000).status().name(); }
        catch (PgException failure) { return failure.code(); }
    }
}
