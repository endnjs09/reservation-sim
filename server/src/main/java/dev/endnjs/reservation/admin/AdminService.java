package dev.endnjs.reservation.admin;

import java.time.Clock;
import dev.endnjs.reservation.config.ResetRequest;
import dev.endnjs.reservation.config.RuntimeConfig;
import dev.endnjs.reservation.config.RuntimeConfigStore;
import dev.endnjs.reservation.metrics.MetricsService;
import dev.endnjs.reservation.admission.SlotNotifier;
import dev.endnjs.reservation.admission.KeyRegistry;
import dev.endnjs.reservation.seat.SeatRepository;
import dev.endnjs.reservation.sale.SaleService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminService implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final SeatRepository seats;
    private final RuntimeConfigStore configs;
    private final BackstopManager backstop;
    private final MetricsService metrics;
    private final Clock clock;
    private final SlotNotifier notifier;
    private final KeyRegistry registry;
    private final SaleService sale;
    private final TransactionTemplate transactions;
    private final dev.endnjs.reservation.snapshot.SnapshotLocks locks;
    private final dev.endnjs.reservation.seat.SeatsGate gate;

    public AdminService(JdbcTemplate jdbc, SeatRepository seats, RuntimeConfigStore configs,
            BackstopManager backstop, MetricsService metrics, Clock clock, SlotNotifier notifier, KeyRegistry registry, SaleService sale, PlatformTransactionManager manager, dev.endnjs.reservation.snapshot.SnapshotLocks locks, dev.endnjs.reservation.seat.SeatsGate gate) {
        this.gate=gate;
        this.locks=locks; this.jdbc = jdbc; this.seats = seats; this.configs = configs; this.backstop = backstop;
        this.metrics = metrics; this.clock = clock; this.notifier=notifier;this.registry=registry; this.transactions = new TransactionTemplate(manager);
        this.sale = sale;
    }

    @Override
    public void run(ApplicationArguments args) {
        var timeline = transactions.execute(status -> {
            if (jdbc.queryForObject("SELECT count(*) FROM seats", Long.class) == 0) {
                seats.seed(configs.current(), clock.instant());
            }
            backstop.apply(configs.current().dbBackstop());
            return sale.initialize(configs.current());
        });
        sale.publish(timeline);
        configs.replace(configs.current().withSaleTiming(timeline.timeScale(), timeline.saleDurationSec()));
        sale.closeIfDue();
        notifier.reset();registry.reset();
        metrics.reset();
    }

    public synchronized RuntimeConfig reset(ResetRequest request) { return reset(request, System.nanoTime()); }

    public synchronized RuntimeConfig reset(ResetRequest request, long receivedNanos) {
        // 3장: anchorAt이 오면 Clock 기준을 새로 잡는다. 테스트의 MutableClock 등 다른 Clock이면 그대로 둔다.
        if (request.anchorAt() != null && clock instanceof dev.endnjs.reservation.config.AnchoredClock anchored) anchored.anchor(request.anchorAt(), receivedNanos);
        RuntimeConfig next = request.applyTo(configs.current());
        var timeline = transactions.execute(status -> {
            sale.lockExclusive();
            locks.reset();
            jdbc.execute("TRUNCATE payments, reservation_seats, reservations, seats, release_batches RESTART IDENTITY CASCADE");
            seats.seed(next, clock.instant());
            backstop.apply(next.dbBackstop());
            return sale.reset(next, clock.instant(),request.saleEndAt());
        });
        configs.replace(next);gate.reset(); // 새로고침 제한 kid 기록·캐시·카운터 비움
        sale.publish(timeline);
        sale.runEpoch(request.runEpoch());
        notifier.reset();registry.reset();
        metrics.reset();
        return next;
    }
}
