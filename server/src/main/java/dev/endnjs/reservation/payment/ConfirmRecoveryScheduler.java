package dev.endnjs.reservation.payment;

import java.time.Clock;
import dev.endnjs.reservation.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ConfirmRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ConfirmRecoveryScheduler.class);
    private final PaymentRepository payments;
    private final ConfirmService confirms;
    private final Clock clock;
    private final MetricsCollector metrics;
    public ConfirmRecoveryScheduler(PaymentRepository payments, ConfirmService confirms, Clock clock, MetricsCollector metrics) {
        this.payments = payments; this.confirms = confirms; this.clock = clock;
        this.metrics = metrics;
    }
    @Scheduled(fixedDelayString = "${reservation.scheduler.recovery-interval-ms:1000}",
            initialDelayString = "${reservation.scheduler.recovery-interval-ms:1000}")
    public void tick() { runOnce(); }
    public int runOnce() {
        int recovered = 0;
        try {
            for (Payment payment : payments.recoveryCandidates(clock.instant())) {
                try {
                    if (confirms.recover(payment)) recovered++;
                } catch (RuntimeException failure) {
                    log.warn("Recovery of payment {} deferred", payment.id(), failure);
                }
            }
            return recovered;
        } finally { metrics.schedulerFinished("recovery", clock.instant(), recovered); }
    }
}
