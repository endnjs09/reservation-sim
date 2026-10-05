package dev.endnjs.reservation.payment;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import dev.endnjs.reservation.seat.SeatView;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;
import dev.endnjs.reservation.hold.ReservationRepository;
import dev.endnjs.reservation.hold.ReservationStatus;
import dev.endnjs.reservation.seat.Grade;
import dev.endnjs.reservation.seat.SeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationQueryService {
    private final ReservationRepository reservations;
    private final SeatRepository seats;
    private final PaymentRepository payments;
    private final dev.endnjs.reservation.snapshot.SnapshotLocks locks;
    public ReservationQueryService(ReservationRepository reservations, SeatRepository seats, PaymentRepository payments, dev.endnjs.reservation.snapshot.SnapshotLocks locks) {
        this.locks=locks; this.reservations = reservations; this.seats = seats; this.payments = payments;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReservationResponse find(long id) {
        locks.read();
        var reservation = reservations.find(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND, "Reservation not found"));
        var all = seats.forReservation(id);
        var seat = all.getFirst();
        var payment = payments.findByReservation(id).map(p -> new PaymentView(p.id(), p.status())).orElse(null);
        return new ReservationResponse(id, reservation.seatId(), seat.label(), seat.grade(), reservation.userId(),
                reservation.status(), reservation.holdExpiresAt(), payment,
                all.stream().map(SeatView::from).toList(),
                all.stream().mapToInt(s -> s.price()).sum(), reservation.paymentMethod(), reservation.depositDeadline());
    }
    public record ReservationResponse(long reservationId, long seatId, String label, Grade grade,
            String userId, ReservationStatus status, Instant holdExpiresAt, PaymentView payment, List<SeatView> seats, int totalPrice, PaymentMethod paymentMethod, Instant depositDeadline) {}
    public record PaymentView(UUID orderId, PaymentStatus status) {}
}
