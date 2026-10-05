package dev.endnjs.reservation.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;

public record RuntimeConfig(int rows, int cols, List<GradeConfig> grades, int holdTtlSec,
        String strategy, boolean dbBackstop, boolean closeQueueOnSoldOut, int confirmDeadlineSec, int timeScale, int saleDurationSec, int maxSeatsPerUser, int depositDeadlineSec, int returnDelaySec, int reopenWindowSec,
        boolean seatsRateLimitEnabled, double seatsMinIntervalSec, int seatsCacheSec) {
    public RuntimeConfig {
        require(rows >= 1 && rows <= 26, "rows must be between 1 and 26");
        require(cols > 0 && (long) rows * cols <= Integer.MAX_VALUE, "Invalid cols");
        require(grades != null && !grades.isEmpty(), "grades is required");
        Set<Object> names = new HashSet<>();
        long totalRows = 0;
        for (GradeConfig grade : grades) {
            require(grade != null && grade.name() != null && grade.rows() >= 0 && grade.price() >= 0,
                    "Invalid grade");
            require(names.add(grade.name()), "Duplicate grade");
            totalRows += grade.rows();
        }
        require(totalRows == rows, "Sum of grade rows must equal rows");
        require(holdTtlSec > 0 && confirmDeadlineSec > 0, "TTL must be positive");
        require(Set.of(1, 2, 4).contains(timeScale) && saleDurationSec > 0, "Invalid sale timing");
        require(depositDeadlineSec > 0 && returnDelaySec > 0 && reopenWindowSec > 0, "Invalid deposit/resale timing");
        require(maxSeatsPerUser > 0, "maxSeatsPerUser must be positive");
        // GET /seats 새로고침 제한(입장키별 최소 간격)과 좌석 조회 캐시. 둘 다 시뮬레이션 초 (docs/DECISION_CLAUDE.md)
        require(Double.isFinite(seatsMinIntervalSec) && seatsMinIntervalSec > 0 && seatsCacheSec >= 0 && seatsCacheSec <= 60, "Invalid seats refresh/cache");
        require(strategy != null && Set.of("conditional", "pessimistic", "optimistic", "naive").contains(strategy),
                "Unknown hold strategy");
        grades = List.copyOf(grades);
    }

    public static RuntimeConfig from(ReservationProperties p) {
        return new RuntimeConfig(p.seats().rows(), p.seats().cols(), p.grades(), p.holdTtlSec(),
                p.strategy(), p.dbBackstop(), p.closeQueueOnSoldOut(),
                p.payment().confirmDeadlineSec(), p.timeScale() == null ? 4 : p.timeScale(),
                p.saleDurationSec() == null ? 1200 : p.saleDurationSec(),
                p.maxSeatsPerUser() == null ? 4 : p.maxSeatsPerUser(),
                p.depositDeadlineSec() == null ? 60 : p.depositDeadlineSec(),
                p.returnDelaySec() == null ? 30 : p.returnDelaySec(),
                p.reopenWindowSec() == null ? 50 : p.reopenWindowSec(),
                true, 1.0, 0);
    }

    public Duration realDuration(int simulationSeconds) {
        return Duration.ofSeconds(simulationSeconds).dividedBy(timeScale);
    }

    public RuntimeConfig withSaleTiming(int scale, int duration) {
        return new RuntimeConfig(rows, cols, grades, holdTtlSec, strategy, dbBackstop, closeQueueOnSoldOut, confirmDeadlineSec, scale, duration, maxSeatsPerUser, depositDeadlineSec, returnDelaySec, reopenWindowSec,
                seatsRateLimitEnabled, seatsMinIntervalSec, seatsCacheSec);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
