package dev.endnjs.reservation.payment;

import java.time.Clock;
import java.util.UUID;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;
import dev.endnjs.reservation.config.RuntimeConfigStore;
import dev.endnjs.reservation.hold.Reservation;
import dev.endnjs.reservation.hold.ReservationRepository;
import dev.endnjs.reservation.hold.ReservationStatus;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.admission.AdmissionAccess;
import dev.endnjs.reservation.seat.SeatRepository;
import dev.endnjs.reservation.sale.SaleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ConfirmService {
    private static final Logger log = LoggerFactory.getLogger(ConfirmService.class);
    private final PaymentRepository payments;
    private final ReservationRepository reservations;
    private final SeatRepository seats;
    private final RuntimeConfigStore configs;
    private final AdmissionAccess queue;
    private final Clock clock;
    private final PgClient pg;
    private final MetricsCollector metrics;
    private final PaymentCoordinator coordinator;
    private final TransactionTemplate transactions;
    private final SaleService sale;

    public ConfirmService(PaymentRepository payments, ReservationRepository reservations, SeatRepository seats,
            RuntimeConfigStore configs, AdmissionAccess queue, Clock clock, PgClient pg, MetricsCollector metrics,
            PaymentCoordinator coordinator, SaleService sale, PlatformTransactionManager manager) {
        this.payments = payments; this.reservations = reservations; this.seats = seats; this.configs = configs;
        this.queue = queue; this.clock = clock; this.pg = pg; this.metrics = metrics; this.coordinator = coordinator;
        this.sale = sale;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ConfirmResult confirm(String userId, UUID orderId, String key, int amount, String token) {
        metrics.increment("confirms");
        synchronized (coordinator.lock(orderId)) {
            Payment payment;
            try {
                payment = transactions.execute(tx -> prepare(userId, orderId, key, amount, token));
            } catch (ApiException exception) {
                if (exception.code() == ErrorCode.RESERVATION_NOT_PAYABLE) metrics.increment("notPayable");
                throw exception;
            }
            Resolution resolution;
            try {
                var approval = pg.confirm(key, orderId, amount);
                resolution = approval.approved()
                        ? finish(payment, PaymentStatus.APPROVED, null)
                        : finish(payment, PaymentStatus.FAILED, approval.failReason());
            } catch (PgUnavailableException unknown) {
                resolution = reconcile(payment);
            }
            if (resolution.status() == ReservationStatus.CONFIRMED) return new ConfirmResult(200, resolution.status());
            if (resolution.status() == ReservationStatus.CONFIRMING) return new ConfirmResult(202, resolution.status());
            throw new ApiException(ErrorCode.PAYMENT_DECLINED, "Payment was not approved");
        }
    }

    private Payment prepare(String userId, UUID orderId, String key, int amount, String token) {
        queue.check(token, userId);
        var payment = payments.lock(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_PAYABLE, "Order not found"));
        var reservation = reservations.lock(payment.reservationId()).orElseThrow();
        var now = clock.instant();
        if (!reservation.userId().equals(userId) || reservation.status() != ReservationStatus.HELD
                || sale.ended() || !now.isBefore(reservation.holdExpiresAt()) || payment.amount() != amount
                || payment.status() != PaymentStatus.REQUESTED) {
            throw new ApiException(ErrorCode.RESERVATION_NOT_PAYABLE, "Reservation is not payable");
        }
        reservations.beginConfirm(reservation.id(), now, now.plus(configs.current().realDuration(configs.current().confirmDeadlineSec())));
        payments.attachKey(orderId, key, now);
        return new Payment(orderId, payment.reservationId(), amount, PaymentStatus.REQUESTED, key);
    }

    /** Every invocation starts with a new PG status lookup, including after an earlier failed cancellation. */
    private Resolution reconcile(Payment payment) {
        try {
            return switch (pg.status(payment.paymentKey())) {
                case DONE -> finish(payment, PaymentStatus.APPROVED, null);
                case CANCELED -> finish(payment, PaymentStatus.CANCELED, "PG_CANCELED");
                case MISSING -> finish(payment, PaymentStatus.FAILED, "PG_PAYMENT_NOT_FOUND");
                case AUTHORIZED, DECLINED -> {
                    pg.cancel(payment.paymentKey());
                    yield finish(payment, PaymentStatus.CANCELED, "PG_CANCELED");
                }
            };
        } catch (PgUnavailableException unavailable) {
            log.info("Payment {} remains CONFIRMING: {}", payment.id(), unavailable.getMessage());
            return new Resolution(ReservationStatus.CONFIRMING, false, 0);
        }
    }

    public boolean recover(Payment candidate) {
        synchronized (coordinator.lock(candidate.id())) {
            Payment payment = transactions.execute(tx -> {
                var latest = payments.lock(candidate.id());
                if (latest.isEmpty()) return null;
                var reservation = reservations.lock(latest.get().reservationId());
                if (reservation.isEmpty() || reservation.get().status() != ReservationStatus.CONFIRMING
                        || latest.get().status() != PaymentStatus.REQUESTED
                        || reservation.get().confirmDeadline() == null
                        || clock.instant().isBefore(reservation.get().confirmDeadline())) return null;
                return latest.get();
            });
            if (payment == null) return false;
            metrics.increment("recoveryAttempts");
            Resolution result = reconcile(payment);
            if (result.changed()) metrics.increment("recovered");
            return result.changed();
        }
    }

    private Resolution finish(Payment payment, PaymentStatus outcome, String reason) {
        Resolution result = transactions.execute(tx -> {
            var latest = payments.lock(payment.id()).orElseThrow();
            Reservation reservation = reservations.lock(latest.reservationId()).orElseThrow();
            if (reservation.status() != ReservationStatus.CONFIRMING || latest.status() != PaymentStatus.REQUESTED) {
                log.info("Ignoring late result for payment {} in reservation state {}", payment.id(), reservation.status());
                return new Resolution(reservation.status(), false, 0);
            }
            var now = clock.instant();
            ReservationStatus next;
            int returned=0;
            if (outcome == PaymentStatus.APPROVED) {
                if (!seats.sellReservation(reservation.id(), now)) {
                    throw new IllegalStateException("Approved reservation no longer owns its held seats");
                }
                next = ReservationStatus.CONFIRMED;
                
            } else {
                next = ReservationStatus.PAYMENT_FAILED;
                returned=seats.releaseReservation(reservation.id(), now);
            }
            reservations.transition(reservation.id(), ReservationStatus.CONFIRMING, next, now);
            payments.finish(payment.id(), outcome, reason, now);
            return new Resolution(next, true, returned);
        });
        metrics.add("immediateReturns",result.returned());
        if (result.changed()) queue.seatsChanged();
        if (result.changed() && result.status() == ReservationStatus.PAYMENT_FAILED) metrics.increment("declines");
        return result;
    }
    public record ConfirmResult(int httpStatus, ReservationStatus status) {}
    private record Resolution(ReservationStatus status, boolean changed, int returned) {}
}
