package dev.endnjs.reservation.config;

import java.util.List;

public record ResetRequest(Integer rows, Integer cols, List<GradeConfig> grades, Integer holdTtlSec,
        String strategy, Boolean dbBackstop, Boolean closeQueueOnSoldOut, Integer confirmDeadlineSec, Integer timeScale, Integer saleDurationSec, Integer maxSeatsPerUser, Integer depositDeadlineSec, Integer returnDelaySec, Integer reopenWindowSec, String runEpoch, java.time.Instant saleEndAt, java.time.Instant anchorAt,
        Boolean seatsRateLimitEnabled, Double seatsMinIntervalSec, Integer seatsCacheSec) {
    public RuntimeConfig applyTo(RuntimeConfig c) {
        return new RuntimeConfig(value(rows, c.rows()), value(cols, c.cols()),
                grades == null ? c.grades() : grades, value(holdTtlSec, c.holdTtlSec()),
                value(strategy, c.strategy()), value(dbBackstop, c.dbBackstop()),
                value(closeQueueOnSoldOut, c.closeQueueOnSoldOut()), value(confirmDeadlineSec, c.confirmDeadlineSec()),
                value(timeScale, c.timeScale()), value(saleDurationSec, c.saleDurationSec()), value(maxSeatsPerUser, c.maxSeatsPerUser()), value(depositDeadlineSec, c.depositDeadlineSec()),
                value(returnDelaySec, c.returnDelaySec()), value(reopenWindowSec, c.reopenWindowSec()),
                value(seatsRateLimitEnabled, c.seatsRateLimitEnabled()), value(seatsMinIntervalSec, c.seatsMinIntervalSec()), value(seatsCacheSec, c.seatsCacheSec()));
    }
    private static <T> T value(T supplied, T existing) { return supplied == null ? existing : supplied; }
}
