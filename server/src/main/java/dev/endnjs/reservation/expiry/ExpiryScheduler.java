package dev.endnjs.reservation.expiry;

import java.sql.Timestamp;
import java.time.Clock;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.admission.AdmissionAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ExpiryScheduler {
    private static final String EXPIRE = """
            WITH expired AS (
              SELECT id FROM reservations
              WHERE status='HELD' AND hold_expires_at <= ?
              ORDER BY hold_expires_at LIMIT ? FOR UPDATE SKIP LOCKED
            ), upd AS (
              UPDATE reservations r SET status='EXPIRED', updated_at=?
              FROM expired e WHERE r.id=e.id RETURNING r.id,r.admission_kid
            ), ordered AS MATERIALIZED (
              SELECT s.id,rs.reservation_id FROM seats s
              JOIN reservation_seats rs ON rs.seat_id=s.id JOIN upd ON upd.id=rs.reservation_id
              ORDER BY s.id FOR UPDATE OF s
            ), released AS (
              UPDATE seats s SET status='AVAILABLE', current_reservation_id=NULL,
                version=s.version+1, updated_at=?
              FROM ordered o WHERE s.id=o.id AND s.current_reservation_id=o.reservation_id AND s.status='HELD'
              RETURNING s.id
            ), marked AS (
              UPDATE reservation_seats rs SET released_at=? FROM upd
              WHERE rs.reservation_id=upd.id AND rs.released_at IS NULL RETURNING rs.seat_id
            ) SELECT (SELECT count(*) FROM upd) AS expired,(SELECT count(*) FROM released) AS returned,(SELECT array_agg(admission_kid) FROM upd WHERE admission_kid IS NOT NULL) AS kids
            """;
    private final JdbcTemplate jdbc;
    private final dev.endnjs.reservation.admission.SlotNotifier notifier;
    private final Clock clock;
    private final MetricsCollector metrics;
    private final AdmissionAccess queue;
    private final int batchSize;
    private final TransactionTemplate transactions;

    public ExpiryScheduler(JdbcTemplate jdbc, Clock clock, MetricsCollector metrics, AdmissionAccess queue,
            @Value("${reservation.scheduler.expiry-batch-size:500}") int batchSize,
            PlatformTransactionManager manager,dev.endnjs.reservation.admission.SlotNotifier notifier) {
        if (batchSize <= 0) throw new IllegalArgumentException("expiry-batch-size must be positive");
        this.notifier=notifier;this.jdbc = jdbc; this.clock = clock; this.metrics = metrics; this.batchSize = batchSize; this.queue = queue;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${reservation.scheduler.expiry-interval-ms:1000}",
            initialDelayString = "${reservation.scheduler.expiry-interval-ms:1000}")
    public void tick() { runOnce(); }

    public int runOnce() {
        int total = 0;
        try {
            int expired;
            do {
                Timestamp now = Timestamp.from(clock.instant());
                var result = transactions.execute(status -> {
                    var batch=jdbc.queryForObject(EXPIRE,(rs,row)-> {
                        var keys=rs.getArray("kids");
                        return new Object[]{rs.getInt("expired"),rs.getInt("returned"),keys==null ? new java.util.UUID[0] : (java.util.UUID[])keys.getArray()};
                    },now,batchSize,now,now,now);
                    for(var kid:(java.util.UUID[])batch[2]) notifier.clearedAfterCommit(kid,now.toInstant(),jdbc);
                    return new int[]{(Integer)batch[0],(Integer)batch[1]};
                });
                expired=result[0];
                metrics.add("immediateReturns",result[1]);
                metrics.add("expiredByScheduler", expired);
                total += expired;
            } while (expired == batchSize);
            if (total > 0) queue.seatsChanged();
            return total;
        } finally { metrics.schedulerFinished("expiry", clock.instant(), total); }
    }
}
