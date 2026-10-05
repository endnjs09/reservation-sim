package dev.endnjs.reservation.resale;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReleaseBatchRepository {
    public record Batch(long id, Instant releaseAt, int seatCount) {}
    public record Summary(long open, Instant nextReleaseAt, long totalReleased, Instant lastReopenAt) {}
    public record Released(int batches, int seats) {}
    private final JdbcTemplate jdbc;
    public ReleaseBatchRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** Sale lock precedes this global transaction lock; expiry/reopen/closure never invert the order. */
    public void lock() { jdbc.query("SELECT pg_advisory_xact_lock(73410, 2)", rs -> {}); }
    public Batch openOrCreate(Instant releaseAt) {
        var open = jdbc.query("SELECT * FROM release_batches WHERE released=FALSE ORDER BY id FOR UPDATE",
                (rs, row) -> new Batch(rs.getLong("id"), rs.getTimestamp("release_at").toInstant(), rs.getInt("seat_count")));
        if (!open.isEmpty()) return open.getFirst();
        return jdbc.queryForObject("INSERT INTO release_batches(release_at) VALUES (?) RETURNING *",
                (rs, row) -> new Batch(rs.getLong("id"), rs.getTimestamp("release_at").toInstant(), 0), Timestamp.from(releaseAt));
    }
    public void addSeats(long id, int count) { jdbc.update("UPDATE release_batches SET seat_count=seat_count+? WHERE id=?", count, id); }
    public Released releaseDue(Instant now, boolean ended) {
        lock();
        var ids = jdbc.query("SELECT id FROM release_batches WHERE released=FALSE AND (? OR release_at<=?) ORDER BY id FOR UPDATE",
                (rs, row) -> rs.getLong(1), ended, Timestamp.from(now));
        int seats = 0;
        for (long id : ids) {
            jdbc.query("SELECT id FROM seats WHERE release_batch_id=? AND status='RETURN_PENDING' ORDER BY id FOR UPDATE", rs -> {}, id);
            seats += jdbc.update("""
                    UPDATE seats SET status='AVAILABLE',current_reservation_id=NULL,release_batch_id=NULL,
                      version=version+1,updated_at=? WHERE release_batch_id=? AND status='RETURN_PENDING'
                    """, Timestamp.from(now), id);
            jdbc.update("UPDATE release_batches SET released=TRUE,released_at=?,reopened=? WHERE id=?", Timestamp.from(now), !ended, id);
        }
        return new Released(ids.size(), seats);
    }
    public Summary summary() {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER(WHERE NOT released) AS open,
                  min(release_at) FILTER(WHERE NOT released) AS next,
                  count(*) FILTER(WHERE released) AS total,
                  max(released_at) FILTER(WHERE reopened) AS last FROM release_batches
                """, (rs, row) -> new Summary(rs.getLong("open"),
                    rs.getTimestamp("next") == null ? null : rs.getTimestamp("next").toInstant(), rs.getLong("total"),
                    rs.getTimestamp("last") == null ? null : rs.getTimestamp("last").toInstant()));
    }
}
