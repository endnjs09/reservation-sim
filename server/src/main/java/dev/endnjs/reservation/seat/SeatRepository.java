package dev.endnjs.reservation.seat;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import dev.endnjs.reservation.config.GradeConfig;
import dev.endnjs.reservation.config.RuntimeConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class SeatRepository {
    private static final RowMapper<SeatInfo> MAPPER = (rs, row) -> new SeatInfo(rs.getLong("id"),
            rs.getString("label"), rs.getInt("row_index"), rs.getInt("col_index"),
            Grade.valueOf(rs.getString("grade")), rs.getInt("price"), SeatStatus.valueOf(rs.getString("status")));
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public SeatRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc); }

    public Optional<SeatInfo> find(long id) {
        return jdbc.query("SELECT * FROM seats WHERE id = ?", MAPPER, id).stream().findFirst();
    }
    public Optional<SeatInfo> lock(long id) {
        return jdbc.query("SELECT * FROM seats WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
    }
    public List<SeatInfo> findAll() { return jdbc.query("SELECT * FROM seats ORDER BY id", MAPPER); }
    public List<SeatInfo> find(List<Long> ids) {
        return named.query("SELECT * FROM seats WHERE id IN (:ids) ORDER BY id", Map.of("ids", ids), MAPPER);
    }
    public List<SeatInfo> lock(List<Long> ids) {
        return named.query("SELECT * FROM seats WHERE id IN (:ids) ORDER BY id FOR UPDATE", Map.of("ids", ids), MAPPER);
    }
    public List<SeatInfo> forReservation(long id) {
        return jdbc.query("""
                SELECT s.* FROM seats s JOIN reservation_seats rs ON rs.seat_id=s.id
                WHERE rs.reservation_id=? ORDER BY s.id
                """, MAPPER, id);
    }
    public List<Long> conditionalHold(List<Long> ids, Instant now) {
        // Lock candidates in a stable order before the set UPDATE (its scan order alone is not guaranteed).
        return named.query("""
                WITH ordered AS MATERIALIZED (
                  SELECT id FROM seats WHERE id IN (:ids) ORDER BY id FOR UPDATE
                ) UPDATE seats s SET status='HELD',version=s.version+1,updated_at=:now
                  FROM (SELECT array_agg(id) AS ids FROM ordered) locked
                  WHERE s.id=ANY(locked.ids) AND s.status='AVAILABLE' RETURNING s.id
                """, Map.of("ids", ids, "now", Timestamp.from(now)), (rs, row) -> rs.getLong(1));
    }
    public void attachReservation(List<Long> ids, long reservationId) {
        named.update("UPDATE seats SET current_reservation_id=:reservation WHERE id IN (:ids)",
                Map.of("ids", ids, "reservation", reservationId));
    }
    private void lockReservationSeats(long id) {
        jdbc.query("""
                SELECT s.id FROM seats s JOIN reservation_seats rs ON rs.seat_id=s.id
                WHERE rs.reservation_id=? ORDER BY s.id FOR UPDATE OF s
                """, rs -> {}, id);
    }
    public int releaseReservation(long id, Instant now) {
        lockReservationSeats(id);
        int returned = jdbc.update("""
                UPDATE seats s SET status='AVAILABLE',current_reservation_id=NULL,version=s.version+1,updated_at=?
                FROM reservation_seats rs WHERE rs.reservation_id=? AND rs.seat_id=s.id
                  AND s.current_reservation_id=? AND s.status='HELD'
                """, Timestamp.from(now), id, id);
        jdbc.update("UPDATE reservation_seats SET released_at=? WHERE reservation_id=? AND released_at IS NULL",
                Timestamp.from(now), id);
        return returned;
    }
    public boolean sellReservation(long id, Instant now) {
        lockReservationSeats(id);
        int count = jdbc.update("""
                UPDATE seats s SET status='SOLD',version=s.version+1,updated_at=?
                FROM reservation_seats rs WHERE rs.reservation_id=? AND rs.seat_id=s.id
                  AND s.current_reservation_id=? AND s.status='HELD' AND rs.released_at IS NULL
                """, Timestamp.from(now), id, id);
        return count == jdbc.queryForObject("SELECT seat_count FROM reservations WHERE id=?", Integer.class, id);
    }

    public int transitionOwned(long id, SeatStatus from, SeatStatus to, Instant now, Long batchId) {
        lockReservationSeats(id);
        return jdbc.update("""
                UPDATE seats s SET status=?,version=s.version+1,updated_at=?,release_batch_id=?,
                  current_reservation_id=CASE WHEN ?='AVAILABLE' THEN NULL ELSE s.current_reservation_id END
                FROM reservation_seats rs WHERE rs.reservation_id=? AND rs.seat_id=s.id
                  AND s.current_reservation_id=? AND s.status=? AND rs.released_at IS NULL
                """, to.name(), Timestamp.from(now), batchId, to.name(), id, id, from.name());
    }
    public void markReleased(long id, Instant now) {
        jdbc.update("UPDATE reservation_seats SET released_at=? WHERE reservation_id=? AND released_at IS NULL", Timestamp.from(now), id);
    }
    public boolean conditionalHold(long id, Instant now) {
        return jdbc.update("""
                UPDATE seats SET status='HELD', version=version+1, updated_at=?
                WHERE id=? AND status='AVAILABLE'
                """, Timestamp.from(now), id) == 1;
    }
    public void unconditionalHold(long id, Instant now) {
        jdbc.update("UPDATE seats SET status='HELD', version=version+1, updated_at=? WHERE id=?",
                Timestamp.from(now), id);
    }
    public void seed(RuntimeConfig config, Instant now) {
        String sql = """
                INSERT INTO seats(id,label,row_index,col_index,grade,price,status,version,updated_at)
                VALUES (?,?,?,?,?,?,'AVAILABLE',0,?)
                """;
        int row = 0;
        for (GradeConfig grade : config.grades()) {
            for (int n = 0; n < grade.rows(); n++, row++) {
                for (int col = 0; col < config.cols(); col++) {
                    long id = (long) row * config.cols() + col + 1;
                    jdbc.update(sql, id, String.valueOf((char) ('A' + row)) + (col + 1), row, col,
                            grade.name().name(), grade.price(), Timestamp.from(now));
                }
            }
        }
    }
}
