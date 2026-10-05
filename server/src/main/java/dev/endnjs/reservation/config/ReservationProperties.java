package dev.endnjs.reservation.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("reservation")
public record ReservationProperties(String strategy, boolean dbBackstop, int holdTtlSec,
        Seats seats, List<GradeConfig> grades, boolean closeQueueOnSoldOut, Payment payment, Integer timeScale, Integer saleDurationSec, Integer maxSeatsPerUser, Integer depositDeadlineSec, Integer returnDelaySec, Integer reopenWindowSec) {
    public record Seats(int rows, int cols) {}
    public record Payment(int confirmDeadlineSec) {}
}
