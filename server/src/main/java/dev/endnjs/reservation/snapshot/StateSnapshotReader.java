package dev.endnjs.reservation.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import dev.endnjs.reservation.metrics.MetricsRepository;
import dev.endnjs.reservation.seat.SeatInfo;
import dev.endnjs.reservation.seat.SeatRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only repeatable snapshots run after mutation transactions release their connections and locks. */
@Component
public class StateSnapshotReader {
    public record SeatView(List<SeatInfo> seats, AvailabilitySummary summary) {}
    public record MetricsView(MetricsRepository.Seats seats, AvailabilitySummary summary) {}
    private final SeatRepository seats;
    private final MetricsRepository metrics;
    private final AvailabilityRepository availability;
    private final TransactionTemplate reads;
    private final SnapshotLocks locks;
    public StateSnapshotReader(SeatRepository seats,MetricsRepository metrics,
            AvailabilityRepository availability,PlatformTransactionManager manager,SnapshotLocks locks) {
        this.locks=locks; this.seats=seats; this.metrics=metrics; this.availability=availability;
        reads=new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
    }
    public SeatView seats() { return reads.execute(tx -> { locks.read(); return new SeatView(seats.findAll(),availability.read()); }); }
    public MetricsView metrics(Instant now) {
        return reads.execute(tx -> { locks.read(); return new MetricsView(metrics.seats(now),availability.read()); });
    }
}
