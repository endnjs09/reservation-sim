package dev.endnjs.reservation.payment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {
    private static final RowMapper<Payment> MAPPER = (rs, row) -> new Payment(rs.getObject("id", UUID.class),
            rs.getLong("reservation_id"), rs.getInt("amount"), PaymentStatus.valueOf(rs.getString("status")),
            rs.getString("payment_key"));
    private final JdbcTemplate jdbc;
    public PaymentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<Payment> findByReservation(long id) {
        return jdbc.query("SELECT * FROM payments WHERE reservation_id=?", MAPPER, id).stream().findFirst();
    }
    public Optional<Payment> lock(UUID id) {
        return jdbc.query("SELECT * FROM payments WHERE id=? FOR UPDATE", MAPPER, id).stream().findFirst();
    }
    public Payment create(long reservationId, int amount, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO payments(id,reservation_id,amount,status,created_at,updated_at)
                VALUES (?,?,?,'REQUESTED',?,?) RETURNING *
                """, MAPPER, UUID.randomUUID(), reservationId, amount, Timestamp.from(now), Timestamp.from(now));
    }
    public void attachKey(UUID id, String key, Instant now) {
        jdbc.update("UPDATE payments SET payment_key=?, updated_at=? WHERE id=?", key, Timestamp.from(now), id);
    }
    public void finish(UUID id, PaymentStatus status, String reason, Instant now) {
        jdbc.update("UPDATE payments SET status=?, fail_reason=?, updated_at=? WHERE id=?",
                status.name(), reason, Timestamp.from(now), id);
    }
    public List<Payment> recoveryCandidates(Instant now) {
        return jdbc.query("""
                SELECT p.* FROM payments p JOIN reservations r ON r.id=p.reservation_id
                WHERE r.status='CONFIRMING' AND r.confirm_deadline <= ?
                ORDER BY r.confirm_deadline,r.id
                """, MAPPER, Timestamp.from(now));
    }
}
