package dev.endnjs.reservation.snapshot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Acquire table locks before the first MVCC snapshot, in the same order as reset. */
@Component
public class SnapshotLocks {
    private static final String TABLES =
            "payments, reservation_seats, reservations,  seats, release_batches, sale_lifecycle";
    private final JdbcTemplate jdbc;

    public SnapshotLocks(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ACCESS SHARE permits ordinary writes; only reset's TRUNCATE needs to wait.
    public void read() { jdbc.execute("LOCK TABLE " + TABLES + " IN ACCESS SHARE MODE"); }
    public void reset() { jdbc.execute("LOCK TABLE " + TABLES + " IN ACCESS EXCLUSIVE MODE"); }
}
