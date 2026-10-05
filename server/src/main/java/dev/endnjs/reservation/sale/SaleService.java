package dev.endnjs.reservation.sale;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;
import dev.endnjs.reservation.config.RuntimeConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persistent reset timeline; transaction-scoped shared/exclusive locks order sale closure before business locks. */
@Service
public class SaleService {
    private final JdbcTemplate jdbc;
    private final org.springframework.beans.factory.ObjectProvider<dev.endnjs.reservation.admission.SlotNotifier> notifier;
    private final Clock clock;
    private final dev.endnjs.reservation.resale.ReleaseBatchRepository batches;
    private final TransactionTemplate transactions;
    private volatile Timeline timeline;
    private volatile String runEpoch=java.util.UUID.randomUUID().toString();
    public String runEpoch() { return runEpoch; }
    public void runEpoch(String supplied) { runEpoch=supplied==null ? java.util.UUID.randomUUID().toString() : supplied; }

    public record Timeline(Instant startedAt, Instant saleEndAt, int timeScale, int saleDurationSec) {
        public boolean ended(Instant now) { return !now.isBefore(saleEndAt); }
        public double elapsedSec(Instant now) {
            return Math.max(0, Duration.between(startedAt, now).toMillis() / 1000.0) * timeScale;
        }
    }
    public record Closure(boolean processed, int expired, int returnedSeats) {}

    public SaleService(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager, dev.endnjs.reservation.resale.ReleaseBatchRepository batches,org.springframework.beans.factory.ObjectProvider<dev.endnjs.reservation.admission.SlotNotifier> notifier) {
        this.notifier=notifier;this.jdbc = jdbc; this.clock = clock; this.batches=batches; this.transactions = new TransactionTemplate(manager);
    }
    public Timeline timeline() { return timeline; }
    public boolean ended() { return timeline != null && timeline.ended(clock.instant()); }

    // Separate two-int lock namespace from existing per-user bigint advisory locks.
    public void lockShared() { jdbc.query("SELECT pg_advisory_xact_lock_shared(73410, 1)", rs -> {}); }
    public void lockExclusive() { jdbc.query("SELECT pg_advisory_xact_lock(73410, 1)", rs -> {}); }
    public void requireOpen() {
        lockShared();
        if (ended()) throw new ApiException(ErrorCode.SALE_ENDED, "Sale has ended");
    }
    @org.springframework.transaction.annotation.Transactional
    public void recordZeroAvailability() {
        lockShared();
        jdbc.update("UPDATE sale_lifecycle SET ever_zero=TRUE WHERE id=1 AND ever_zero=FALSE");
    }
    public boolean zeroObserved() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ever_zero FROM sale_lifecycle WHERE id=1",Boolean.class));
    }
    /** Called in admin's transaction; publish only after that transaction commits. */
    public Timeline reset(RuntimeConfig config, Instant now) { return reset(config,now,null); }
    public Timeline reset(RuntimeConfig config,Instant now,Instant suppliedEnd) {
        lockExclusive();
        now = now.truncatedTo(ChronoUnit.MICROS);
        Instant end = suppliedEnd==null ? now.plus(config.realDuration(config.saleDurationSec())) : suppliedEnd.truncatedTo(ChronoUnit.MICROS);
        if(suppliedEnd!=null) now=end.minus(config.realDuration(config.saleDurationSec()));
        jdbc.update("""
                INSERT INTO sale_lifecycle(id,started_at,sale_end_at,time_scale,sale_duration_sec,ended_at)
                VALUES (1,?,?,?,?,NULL) ON CONFLICT (id) DO UPDATE SET
                  started_at=EXCLUDED.started_at,sale_end_at=EXCLUDED.sale_end_at,
                  time_scale=EXCLUDED.time_scale,sale_duration_sec=EXCLUDED.sale_duration_sec,ended_at=NULL,ever_zero=FALSE
                """, Timestamp.from(now), Timestamp.from(end), config.timeScale(), config.saleDurationSec());
        return new Timeline(now, end, config.timeScale(), config.saleDurationSec());
    }
    public Timeline initialize(RuntimeConfig config) {
        lockExclusive();
        var existing = jdbc.query("SELECT * FROM sale_lifecycle WHERE id=1", (rs, row) ->
                new Timeline(rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("sale_end_at").toInstant(),
                        rs.getInt("time_scale"), rs.getInt("sale_duration_sec")));
        return existing.isEmpty() ? reset(config, clock.instant()) : existing.getFirst();
    }
    public void publish(Timeline value) { timeline = value; }

    public Closure closeIfDue() {
        if (!ended()) return new Closure(false, 0, 0);
        return transactions.execute(tx -> {
            lockExclusive();
            batches.lock();
            // Recheck the persisted deadline after locking: reset may have started another sale while we waited.
            boolean due = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT ended_at IS NULL AND sale_end_at <= ? FROM sale_lifecycle WHERE id=1 FOR UPDATE",
                    Boolean.class, Timestamp.from(clock.instant())));
            if (!due) return new Closure(false, 0, 0);
            Timestamp now = Timestamp.from(clock.instant());
            var returned = jdbc.queryForObject("""
                    WITH expired AS (
                      UPDATE reservations SET status='EXPIRED',updated_at=? WHERE status='HELD'
                      RETURNING id,admission_kid
                    ), ordered AS MATERIALIZED (
                      SELECT s.id,rs.reservation_id FROM seats s
                      JOIN reservation_seats rs ON rs.seat_id=s.id JOIN expired r ON r.id=rs.reservation_id
                      ORDER BY s.id FOR UPDATE OF s
                    ), returned AS (
                      UPDATE seats s SET status='AVAILABLE',current_reservation_id=NULL,
                        version=s.version+1,updated_at=? FROM ordered o
                      WHERE s.id=o.id AND s.current_reservation_id=o.reservation_id AND s.status='HELD' RETURNING s.id
                    ), marked AS (
                      UPDATE reservation_seats rs SET released_at=? FROM expired r
                      WHERE rs.reservation_id=r.id AND rs.released_at IS NULL RETURNING rs.seat_id
                    ) SELECT (SELECT count(*) FROM expired) AS expired,(SELECT count(*) FROM returned) AS seats,(SELECT array_agg(admission_kid) FROM expired WHERE admission_kid IS NOT NULL) AS kids
                    """, (rs,row) -> {
                        var keys=rs.getArray("kids");
                        return new Object[]{rs.getInt("expired"),rs.getInt("seats"),keys==null ? new java.util.UUID[0] : (java.util.UUID[])keys.getArray()};
                    }, now, now, now);
            for(var kid:(java.util.UUID[])returned[2]) notifier.getObject().clearedAfterCommit(kid,now.toInstant(),jdbc);
            batches.releaseDue(clock.instant(),true);
            jdbc.update("UPDATE sale_lifecycle SET ended_at=? WHERE id=1", now);
            return new Closure(true, (Integer)returned[0], (Integer)returned[1]);
        });
    }
}
