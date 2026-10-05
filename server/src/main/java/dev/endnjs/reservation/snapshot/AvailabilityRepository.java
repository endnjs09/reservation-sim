package dev.endnjs.reservation.snapshot;

import dev.endnjs.reservation.config.RuntimeConfigStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AvailabilityRepository {
    private final JdbcTemplate jdbc;
    private final RuntimeConfigStore configs;
    public AvailabilityRepository(JdbcTemplate jdbc, RuntimeConfigStore configs) { this.jdbc=jdbc; this.configs=configs; }
    public AvailabilitySummary read() {
        return jdbc.queryForObject("""
                WITH inventory AS (
                  SELECT count(*) FILTER(WHERE status='AVAILABLE') AS a,count(*) FILTER(WHERE status='HELD') AS h,
                    count(*) FILTER(WHERE status='PENDING_DEPOSIT') AS d,count(*) FILTER(WHERE status='RETURN_PENDING') AS r,
                    count(*) FILTER(WHERE status='SOLD') AS s FROM seats
                ), batches AS (
                  SELECT min(release_at) FILTER(WHERE NOT released) AS next,
                    max(released_at) FILTER(WHERE reopened) AS last FROM release_batches
                ) SELECT inventory.*,batches.*,l.sale_end_at,l.started_at,l.time_scale,l.ever_zero,
                  EXISTS(SELECT 1 FROM reservations) AS has_holds FROM inventory CROSS JOIN batches CROSS JOIN sale_lifecycle l WHERE l.id=1
                """, (rs,row) -> new AvailabilitySummary(rs.getLong("a"),rs.getLong("h"),rs.getLong("d"),rs.getLong("r"),rs.getLong("s"),
                    rs.getTimestamp("next")==null ? null : rs.getTimestamp("next").toInstant(),
                    rs.getTimestamp("sale_end_at").toInstant(),rs.getTimestamp("last")==null ? null : rs.getTimestamp("last").toInstant(),
                    rs.getTimestamp("started_at").toInstant(),rs.getBoolean("has_holds"),rs.getBoolean("ever_zero"),rs.getInt("time_scale"),configs.current().reopenWindowSec()));
    }
}
