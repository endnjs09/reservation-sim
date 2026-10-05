package dev.endnjs.reservation.deposit;

import java.time.Clock;
import java.time.Instant;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;
import dev.endnjs.reservation.config.RuntimeConfigStore;
import dev.endnjs.reservation.hold.ReservationRepository;
import dev.endnjs.reservation.hold.ReservationStatus;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.payment.PaymentMethod;
import dev.endnjs.reservation.admission.AdmissionAccess;
import dev.endnjs.reservation.sale.SaleService;
import dev.endnjs.reservation.seat.SeatRepository;
import dev.endnjs.reservation.seat.SeatStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DepositService {
    private final ReservationRepository reservations;
    private final SeatRepository seats;
    private final RuntimeConfigStore configs;
    private final AdmissionAccess queue;
    private final SaleService sale;
    private final Clock clock;
    private final MetricsCollector metrics;
    private final TransactionTemplate transactions;
    public DepositService(ReservationRepository reservations, SeatRepository seats, RuntimeConfigStore configs,
            AdmissionAccess queue, SaleService sale, Clock clock, MetricsCollector metrics, PlatformTransactionManager manager) {
        this.reservations=reservations; this.seats=seats; this.configs=configs; this.queue=queue;
        this.sale=sale; this.clock=clock; this.metrics=metrics;
        transactions=new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Requested request(long id, String user, String token) {
        Requested result=transactions.execute(tx -> {
            queue.check(token,user);
            var reservation=reservations.lock(id).orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND,"Reservation not found"));
            var now=clock.instant();
            if (!reservation.userId().equals(user) || reservation.status()!=ReservationStatus.HELD
                    || sale.ended() || !now.isBefore(reservation.holdExpiresAt())) {
                throw new ApiException(ErrorCode.RESERVATION_NOT_PAYABLE,"Reservation is not payable");
            }
            Instant deadline=now.plus(configs.current().realDuration(configs.current().depositDeadlineSec()));
            if (seats.transitionOwned(id,SeatStatus.HELD,SeatStatus.PENDING_DEPOSIT,now,null)!=reservation.seatCount()) {
                throw new IllegalStateException("Deposit reservation no longer owns all held seats");
            }
            reservations.beginDeposit(id,deadline,now);
            
            int amount=seats.forReservation(id).stream().mapToInt(s -> s.price()).sum();
            return new Requested(ReservationStatus.PENDING_DEPOSIT,deadline,amount);
        });
        metrics.increment("depositsRequested");
        queue.seatsChanged();
        return result;
    }
    public Paid pay(long id, String user, int amount) {
        Paid result=transactions.execute(tx -> {
            sale.lockShared();
            var reservation=reservations.lock(id).orElseThrow(() -> unacceptable());
            var now=clock.instant();
            if (!reservation.userId().equals(user) || reservation.status()!=ReservationStatus.PENDING_DEPOSIT
                    || reservation.paymentMethod()!=PaymentMethod.DEPOSIT || reservation.depositDeadline()==null
                    || !now.isBefore(reservation.depositDeadline())
                    || amount!=seats.forReservation(id).stream().mapToInt(s -> s.price()).sum()) throw unacceptable();
            if (seats.transitionOwned(id,SeatStatus.PENDING_DEPOSIT,SeatStatus.SOLD,now,null)!=reservation.seatCount()) {
                throw new IllegalStateException("Deposit reservation no longer owns all pending seats");
            }
            reservations.transition(id,ReservationStatus.PENDING_DEPOSIT,ReservationStatus.CONFIRMED,now);
            return new Paid(ReservationStatus.CONFIRMED);
        });
        metrics.increment("depositsPaid");
        queue.seatsChanged();
        return result;
    }
    private static ApiException unacceptable() { return new ApiException(ErrorCode.DEPOSIT_NOT_ACCEPTABLE,"Deposit is not acceptable"); }
    public record Requested(ReservationStatus status, Instant depositDeadline, int amount) {}
    public record Paid(ReservationStatus status) {}
}
