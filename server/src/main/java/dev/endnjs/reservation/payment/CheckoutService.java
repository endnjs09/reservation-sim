package dev.endnjs.reservation.payment;

import java.time.Clock;
import java.util.UUID;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;
import dev.endnjs.reservation.hold.ReservationRepository;
import dev.endnjs.reservation.hold.ReservationStatus;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.admission.AdmissionAccess;
import dev.endnjs.reservation.seat.SeatRepository;
import dev.endnjs.reservation.sale.SaleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutService {
    private final ReservationRepository reservations;
    private final PaymentRepository payments;
    private final SeatRepository seats;
    private final AdmissionAccess queue;
    private final Clock clock;
    private final MetricsCollector metrics;
    private final SaleService sale;
    public CheckoutService(ReservationRepository reservations, PaymentRepository payments, SeatRepository seats,
            AdmissionAccess queue, Clock clock, MetricsCollector metrics, SaleService sale) {
        this.reservations = reservations; this.payments = payments; this.seats = seats;
        this.queue = queue; this.clock = clock; this.metrics = metrics;
        this.sale = sale;
    }

    @Transactional
    public CheckoutResponse checkout(long id, String userId, String token) {
        queue.check(token, userId);
        var reservation = reservations.lock(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND, "Reservation not found"));
        var now = clock.instant();
        if (!reservation.userId().equals(userId) || reservation.status() != ReservationStatus.HELD
                || sale.ended() || !now.isBefore(reservation.holdExpiresAt())) {
            metrics.increment("notPayable");
            throw new ApiException(ErrorCode.RESERVATION_NOT_PAYABLE, "Reservation is not payable");
        }
        Payment payment = payments.findByReservation(id).orElseGet(() ->
                payments.create(id, seats.forReservation(id).stream().mapToInt(s -> s.price()).sum(), now));
        return new CheckoutResponse(payment.id(), payment.amount());
    }
    public record CheckoutResponse(UUID orderId, int amount) {}
}
