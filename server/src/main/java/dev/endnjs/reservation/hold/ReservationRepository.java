package dev.endnjs.reservation.hold;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {
    private static final RowMapper<Reservation> MAPPER = (rs, row) -> new Reservation(rs.getLong("id"),
            rs.getLong("seat_id"), rs.getString("user_id"), ReservationStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("hold_expires_at").toInstant(), rs.getString("idempotency_key"),
            rs.getTimestamp("confirm_deadline") == null ? null : rs.getTimestamp("confirm_deadline").toInstant(),
            dev.endnjs.reservation.payment.PaymentMethod.valueOf(rs.getString("payment_method")),
            rs.getTimestamp("deposit_deadline") == null ? null : rs.getTimestamp("deposit_deadline").toInstant(),
            rs.getInt("seat_count"));
    private final JdbcTemplate jdbc;
    private final dev.endnjs.reservation.admission.SlotNotifier notifier;
    @org.springframework.beans.factory.annotation.Autowired
    public ReservationRepository(JdbcTemplate jdbc,dev.endnjs.reservation.admission.SlotNotifier notifier) { this.jdbc = jdbc; this.notifier=notifier; }
    public ReservationRepository(JdbcTemplate jdbc) { this(jdbc,null); }
    public Optional<Reservation> findByKey(String key) {
        return jdbc.query("SELECT * FROM reservations WHERE idempotency_key=?", MAPPER, key).stream().findFirst();
    }
    public Optional<Reservation> lock(long id) {
        return jdbc.query("SELECT * FROM reservations WHERE id=? FOR UPDATE", MAPPER, id).stream().findFirst();
    }
    public Optional<Reservation> find(long id) {
        return jdbc.query("SELECT * FROM reservations WHERE id=?", MAPPER, id).stream().findFirst();
    }
    public boolean transition(long id, ReservationStatus from, ReservationStatus to, Instant now) {
        boolean changed=jdbc.update("UPDATE reservations SET status=?, updated_at=? WHERE id=? AND status=?",
                to.name(), Timestamp.from(now), id, from.name()) == 1;
        if(changed && (from==ReservationStatus.HELD || from==ReservationStatus.CONFIRMING)) notifyEnded(id,to,now);
        return changed;
    }
    public void beginDeposit(long id, Instant deadline, Instant now) {
        jdbc.update("UPDATE reservations SET status='PENDING_DEPOSIT',payment_method='DEPOSIT',deposit_deadline=?,updated_at=? WHERE id=? AND status='HELD'",
                Timestamp.from(deadline), Timestamp.from(now), id);
        notifyEnded(id,ReservationStatus.PENDING_DEPOSIT,now);
    }
    public List<Reservation> lockExpiredDeposits(Instant now) {
        return jdbc.query("SELECT * FROM reservations WHERE status='PENDING_DEPOSIT' AND deposit_deadline<=? ORDER BY id FOR UPDATE",
                MAPPER, Timestamp.from(now));
    }
    public void beginConfirm(long id, Instant now, Instant deadline) {
        jdbc.update("UPDATE reservations SET status='CONFIRMING', confirm_deadline=?, updated_at=? WHERE id=?",
                Timestamp.from(deadline), Timestamp.from(now), id);
    }
    public void lockUser(String userId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, userId);
    }
    public List<ReservationStatus> activeUserStatuses(String userId) {
        return jdbc.query("""
                SELECT status FROM reservations WHERE user_id=? AND status IN ('HELD','CONFIRMING','CONFIRMED','PENDING_DEPOSIT')
                """, (rs, row) -> ReservationStatus.valueOf(rs.getString("status")), userId);
    }
    public Reservation create(String userId, List<Long> seatIds, String key, Instant now, Instant expires) {
        return create(userId,seatIds,key,now,expires,null);
    }
    public Reservation create(String userId,List<Long> seatIds,String key,Instant now,Instant expires,java.util.UUID kid) {
        Reservation reservation = jdbc.queryForObject("""
                INSERT INTO reservations(seat_id,user_id,status,hold_expires_at,idempotency_key,created_at,updated_at,seat_count,admission_kid)
                VALUES (?,?,'HELD',?,?,?,?,?,?) RETURNING *
                """, MAPPER, seatIds.getFirst(), userId, Timestamp.from(expires), key,
                Timestamp.from(now), Timestamp.from(now), seatIds.size(),kid);
        for (long seatId : seatIds) {
            jdbc.update("INSERT INTO reservation_seats(reservation_id,seat_id) VALUES (?,?)", reservation.id(), seatId);
        }
        if(notifier!=null) notifier.notifyAfterCommit(kid,"HOLD_ACTIVE",now);
        return reservation;
    }
    public List<Long> seatIds(long id) {
        return jdbc.query("SELECT seat_id FROM reservation_seats WHERE reservation_id=? ORDER BY seat_id",
                (rs, row) -> rs.getLong(1), id);
    }
    public void release(long id, Instant now) {
        jdbc.update("UPDATE reservations SET status='RELEASED', updated_at=? WHERE id=? AND status='HELD'",
                Timestamp.from(now), id);
        notifyEnded(id,ReservationStatus.RELEASED,now);
    }
    private void notifyEnded(long id,ReservationStatus status,Instant now) {
        if(notifier==null) return;
        java.util.UUID kid=jdbc.queryForObject("SELECT admission_kid FROM reservations WHERE id=?",java.util.UUID.class,id);
        if(status==ReservationStatus.CONFIRMED || status==ReservationStatus.PENDING_DEPOSIT) notifier.notifyAfterCommit(kid,"COMPLETED",now);
        notifier.clearedAfterCommit(kid,now,jdbc);
    }
}
