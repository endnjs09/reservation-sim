package dev.endnjs.reservation.metrics;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetricsRepository {
    public record Seats(String seatMap, Map<String, Long> heldRemainingMs, long available, long held, long sold, long pendingDeposit, long returnPending, Map<String,Long> depositRemainingMs) {
        public long total() { return seatMap.length(); }
    }
    private final JdbcTemplate jdbc;
    public MetricsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean hasReservations() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM reservations)", Boolean.class));
    }
    public Seats seats(Instant now) {
        StringBuilder map = new StringBuilder();
        Map<String, Long> deposits = new LinkedHashMap<>();
        Map<String, Long> remaining = new LinkedHashMap<>();
        long[] counts = new long[5];
        jdbc.query("""
                SELECT s.id,s.status,r.status AS reservation_status,r.hold_expires_at,r.deposit_deadline FROM seats s
                LEFT JOIN reservations r ON r.id=s.current_reservation_id ORDER BY s.id
                """, rs -> {
            switch (rs.getString("status")) {
                case "AVAILABLE" -> { map.append('A'); counts[0]++; }
                case "HELD" -> {
                    map.append('H'); counts[1]++;
                    Timestamp expires = rs.getTimestamp("hold_expires_at");
                    String status = rs.getString("reservation_status");
                    if (expires != null && ("HELD".equals(status) || "CONFIRMING".equals(status))) {
                        remaining.put(Long.toString(rs.getLong("id")), Duration.between(now, expires.toInstant()).toMillis());
                    }
                }
                case "PENDING_DEPOSIT" -> {
                    map.append('D'); counts[3]++;
                    Timestamp deadline=rs.getTimestamp("deposit_deadline");
                    if (deadline!=null) deposits.put(Long.toString(rs.getLong("id")),Math.max(0,Duration.between(now,deadline.toInstant()).toMillis()));
                }
                case "RETURN_PENDING" -> { map.append('R'); counts[4]++; }
                case "SOLD" -> { map.append('S'); counts[2]++; }
            }
        });
        return new Seats(map.toString(), Map.copyOf(remaining), counts[0], counts[1], counts[2], counts[3], counts[4], Map.copyOf(deposits));
    }

    public MetricsSnapshot.Db locks(Instant now) {
        return jdbc.queryForObject("SELECT count(*) AS n,coalesce(max(extract(epoch from (?::timestamptz-query_start))*1000),0) AS ms FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",
                (rs,row) -> new MetricsSnapshot.Db(rs.getLong("n"),Math.max(0,rs.getDouble("ms"))),Timestamp.from(now));
    }
    public long lockWaits() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'", Long.class);
    }
}
