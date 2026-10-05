package dev.endnjs.reservation.deposit;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import dev.endnjs.reservation.config.RuntimeConfigStore;
import dev.endnjs.reservation.hold.ReservationRepository;
import dev.endnjs.reservation.hold.ReservationStatus;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.admission.AdmissionAccess;
import dev.endnjs.reservation.resale.ReleaseBatchRepository;
import dev.endnjs.reservation.sale.SaleService;
import dev.endnjs.reservation.seat.SeatRepository;
import dev.endnjs.reservation.seat.SeatStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DepositExpiryScheduler {
    private final RuntimeConfigStore configs;
    private final ReservationRepository reservations;
    private final SeatRepository seats;
    private final ReleaseBatchRepository batches;
    private final SaleService sale;
    private final Clock clock;
    private final MetricsCollector metrics;
    private final AdmissionAccess queue;
    private final TransactionTemplate transactions;
    public DepositExpiryScheduler(RuntimeConfigStore configs, ReservationRepository reservations, SeatRepository seats,
            ReleaseBatchRepository batches, SaleService sale, Clock clock, MetricsCollector metrics,
            AdmissionAccess queue, PlatformTransactionManager manager) {
        this.configs=configs; this.reservations=reservations; this.seats=seats; this.batches=batches;
        this.sale=sale; this.clock=clock; this.metrics=metrics; this.queue=queue;
        transactions=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    public void tick() { runOnce(); }
    public int runOnce() {
        int expired=0;
        try {
            Result result=transactions.execute(tx -> {
                sale.lockShared();
                batches.lock();
                var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
                var candidates=reservations.lockExpiredDeposits(now);
                int returned=0;
                for (var reservation:candidates) {
                    boolean ended=sale.ended();
                    var batch=ended ? null : batches.openOrCreate(now.plus(configs.current().realDuration(configs.current().returnDelaySec())));
                    int count=seats.transitionOwned(reservation.id(),SeatStatus.PENDING_DEPOSIT,
                            ended ? SeatStatus.AVAILABLE : SeatStatus.RETURN_PENDING,now,batch==null ? null : batch.id());
                    if (batch!=null) batches.addSeats(batch.id(),count); else returned+=count;
                    reservations.transition(reservation.id(),ReservationStatus.PENDING_DEPOSIT,ReservationStatus.DEPOSIT_EXPIRED,now);
                    seats.markReleased(reservation.id(),now);
                }
                return new Result(candidates.size(),returned);
            });
            expired=result.expired();
            metrics.add("depositsExpired",expired);
            metrics.add("immediateReturns",result.returned());
            metrics.event("DEPOSIT_EXPIRED",expired);
            if (expired>0) queue.seatsChanged();
            return expired;
        } finally { metrics.schedulerFinished("depositExpiry",clock.instant(),expired); }
    }
    private record Result(int expired,int returned) {}
}
