package dev.endnjs.reservation.payment;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;
import dev.endnjs.reservation.hold.Reservation;
import dev.endnjs.reservation.hold.ReservationRepository;
import dev.endnjs.reservation.hold.ReservationStatus;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.admission.AdmissionAccess;
import dev.endnjs.reservation.sale.SaleService;
import dev.endnjs.reservation.seat.SeatRepository;
import dev.endnjs.reservation.seat.SeatStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CancellationService {
    private final ReservationRepository reservations;
    private final PaymentRepository payments;
    private final SeatRepository seats;
    private final PaymentCoordinator coordinator;
    private final PgClient pg;
    private final SaleService sale;
    private final Clock clock;
    private final AdmissionAccess queue;
    private final MetricsCollector metrics;
    private final TransactionTemplate transactions;
    public CancellationService(ReservationRepository reservations,PaymentRepository payments,SeatRepository seats,
            PaymentCoordinator coordinator,PgClient pg,SaleService sale,Clock clock,AdmissionAccess queue,
            MetricsCollector metrics,PlatformTransactionManager manager) {
        this.reservations=reservations; this.payments=payments; this.seats=seats; this.coordinator=coordinator;
        this.pg=pg; this.sale=sale; this.clock=clock; this.queue=queue; this.metrics=metrics;
        transactions=new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Canceled cancel(long id,String user) {
        var timeline=sale.timeline();
        Reservation original=reservations.find(id).orElseThrow(CancellationService::notCancelable);
        Payment candidate=payments.findByReservation(id).orElse(null);
        UUID coordinationId=candidate==null ? UUID.nameUUIDFromBytes(("reservation:"+id).getBytes(StandardCharsets.UTF_8)) : candidate.id();
        synchronized (coordinator.lock(coordinationId)) {
            Prepared prepared=transactions.execute(tx -> {
                sale.lockShared();
                if (sale.timeline()!=timeline) throw notCancelable();
                Payment payment=candidate==null ? null : payments.lock(candidate.id()).orElse(null);
                var reservation=reservations.lock(id).orElseThrow(CancellationService::notCancelable);
                check(reservation,user,original);
                if (reservation.paymentMethod()==PaymentMethod.DEPOSIT) {
                    return new Prepared(null,finishDeposit(reservation));
                }
                checkPayment(payment,id);
                return new Prepared(payment,0);
            });
            int returned=prepared.returned();
            if (prepared.payment()!=null) {
                try { pg.cancel(prepared.payment().paymentKey()); }
                catch (PgUnavailableException failure) {
                    throw new ApiException(ErrorCode.PG_UNAVAILABLE,"PG cancellation not confirmed");
                }
                returned=transactions.execute(tx -> {
                    sale.lockShared();
                    // A reset can reuse reservation ids. Match the run and the original immutable payment UUID.
                    if (sale.timeline()!=timeline) throw notCancelable();
                    var payment=payments.lock(prepared.payment().id()).orElseThrow(CancellationService::notCancelable);
                    var reservation=reservations.lock(id).orElseThrow(CancellationService::notCancelable);
                    check(reservation,user,original);
                    checkPayment(payment,id);
                    if (reservation.paymentMethod()!=PaymentMethod.CARD) throw notCancelable();
                    int count=seats.transitionOwned(id,SeatStatus.SOLD,SeatStatus.AVAILABLE,clock.instant(),null);
                    seats.markReleased(id,clock.instant());
                    reservations.transition(id,ReservationStatus.CONFIRMED,ReservationStatus.CANCELED,clock.instant());
                    payments.finish(payment.id(),PaymentStatus.CANCELED,"USER_CANCELED",clock.instant());
                    return count;
                });
            }
            metrics.increment("userCancels");
            metrics.add("immediateReturns",returned);
            metrics.event("USER_CANCEL",1);
            queue.seatsChanged();
            return new Canceled(ReservationStatus.CANCELED);
        }
    }
    private int finishDeposit(Reservation reservation) {
        var now=clock.instant();
        int count=seats.transitionOwned(reservation.id(),reservation.status()==ReservationStatus.PENDING_DEPOSIT
                ? SeatStatus.PENDING_DEPOSIT : SeatStatus.SOLD,SeatStatus.AVAILABLE,now,null);
        reservations.transition(reservation.id(),reservation.status(),ReservationStatus.CANCELED,now);
        seats.markReleased(reservation.id(),now);
        return count;
    }
    private static void check(Reservation reservation,String user,Reservation original) {
        if (!reservation.userId().equals(user) || !reservation.idempotencyKey().equals(original.idempotencyKey())
                || (reservation.status()!=ReservationStatus.CONFIRMED && reservation.status()!=ReservationStatus.PENDING_DEPOSIT)
                || (reservation.status()==ReservationStatus.PENDING_DEPOSIT && reservation.paymentMethod()!=PaymentMethod.DEPOSIT)) throw notCancelable();
    }
    private static void checkPayment(Payment payment,long id) {
        if (payment==null || payment.reservationId()!=id || payment.status()!=PaymentStatus.APPROVED || payment.paymentKey()==null) throw notCancelable();
    }
    private static ApiException notCancelable() { return new ApiException(ErrorCode.RESERVATION_NOT_CANCELABLE,"Reservation is not cancelable"); }
    private record Prepared(Payment payment,int returned) {}
    public record Canceled(ReservationStatus status) {}
}
