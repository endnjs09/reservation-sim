package dev.endnjs.reservation.resale;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.admission.AdmissionAccess;
import dev.endnjs.reservation.sale.SaleService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ReopenScheduler {
    private final ReleaseBatchRepository batches;
    private final SaleService sale;
    private final Clock clock;
    private final MetricsCollector metrics;
    private final AdmissionAccess queue;
    private final TransactionTemplate transactions;
    public ReopenScheduler(ReleaseBatchRepository batches,SaleService sale,Clock clock,MetricsCollector metrics,
            AdmissionAccess queue,PlatformTransactionManager manager) {
        this.batches=batches; this.sale=sale; this.clock=clock; this.metrics=metrics; this.queue=queue;
        transactions=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    public void tick() { runOnce(); }
    public int runOnce() {
        int reopened=0;
        try {
            Result result=transactions.execute(tx -> {
                sale.lockShared();
                boolean ended=sale.ended();
                var released=batches.releaseDue(clock.instant().truncatedTo(ChronoUnit.MICROS),ended);
                return new Result(released,ended);
            });
            if (!result.ended()) {
                reopened=result.released().seats();
                metrics.add("reopenCount",result.released().batches());
                metrics.add("reopenSeats",reopened);
                metrics.event("REOPEN",result.released().batches());
            }
            if (result.released().batches()>0) queue.seatsChanged();
            return reopened;
        } finally { metrics.schedulerFinished("reopen",clock.instant(),reopened); }
    }
    private record Result(ReleaseBatchRepository.Released released,boolean ended) {}
}
