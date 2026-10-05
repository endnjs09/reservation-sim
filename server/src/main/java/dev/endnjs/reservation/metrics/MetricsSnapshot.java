package dev.endnjs.reservation.metrics;

import java.time.Instant;
import java.util.Map;

public record MetricsSnapshot(Instant at, Map<String, MetricsCollector.Endpoint> endpoints, int inflightTotal,
        Pool pool, Db db, MetricsCollector.Pg pg, Map<String, Map<String, Object>> schedulers,
        String seatMap, Map<String, Long> heldRemainingMs, Map<String, Long> seatConflicts,
        Phase phase, double simElapsedSec, int timeScale, Instant saleEndAt, Map<String,Long> depositRemainingMs, Instant releaseAt, Instant lastReopenAt,
        long availableSeats, long heldSeats, long pendingDepositSeats, long returnPendingSeats, long soldSeats, Map<String,Long> events, Long idleInside, boolean soldOut, int windowMs, RequestHistograms.Total total, Map<String,Object> cumulative, Map<String,Object> http, Map<String,Object> jvm, Map<String,Object> notifier, double metricsSelfMs, Map<String,Object> seatsCache) {
    public record Pool(int active, int idle, int pending, int max, Map<String,Object> acquireMs, long timeouts) {
        public Pool(int active,int idle,int pending,int max) { this(active,idle,pending,max,java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(java.util.Map.of("available",false))),0); }
    }
    public record Db(long lockWaits,double lockWaitMaxMs) {}
    public enum Phase {
        OPEN, RUSH, RESALE, SOLD_OUT, REOPEN, ENDED;
        public static Phase resolve(boolean ended, boolean hasHolds, boolean everZero,
                boolean reopenWindowActive, long available, long held) {
            if (ended) return ENDED;
            if (!hasHolds) return OPEN;
            if (!everZero) return RUSH;
            if (reopenWindowActive && available > 0) return REOPEN;
            if (available == 0 && held == 0) return SOLD_OUT;
            return RESALE;
        }
    }
}
