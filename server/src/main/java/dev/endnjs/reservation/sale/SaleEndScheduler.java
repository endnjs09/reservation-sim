package dev.endnjs.reservation.sale;

import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.admission.AdmissionAccess;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SaleEndScheduler {
    private final SaleService sale;
    private final AdmissionAccess queues;
    private final MetricsCollector metrics;
    private final Clock clock;
    public SaleEndScheduler(SaleService sale, AdmissionAccess queues, MetricsCollector metrics, Clock clock) {
        this.sale = sale; this.queues = queues; this.metrics = metrics; this.clock = clock;
    }
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void tick() { runOnce(); }
    public int runOnce() {
        int expired = 0;
        try {
            var closure = sale.closeIfDue();
            expired = closure.expired();
            metrics.add("immediateReturns",closure.returnedSeats());
            if (sale.ended()) queues.seatsChanged(); // Also retry cache publication after a previous failure.
            return expired;
        } finally { /* Sale closure is housekeeping, not an additional public scheduler. */ }
    }
}
