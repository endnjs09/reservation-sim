package dev.endnjs.reservation.snapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import dev.endnjs.reservation.metrics.MetricsSnapshot.Phase;

/** Inventory and lifecycle values from one database snapshot; time-only decisions require no database read. */
public record AvailabilitySummary(long availableSeats, long heldSeats, long pendingDepositSeats,
        long returnPendingSeats, long soldSeats, Instant releaseAt, Instant saleEndAt, Instant lastReopenAt, Instant saleStartedAt,
        boolean hasHolds, boolean everZero, int timeScale, int reopenWindowSec) {
    public static AvailabilitySummary empty() {
        return new AvailabilitySummary(0,0,0,0,0,null,null,null,null,false,false,1,50);
    }
    public double simElapsedSec(Instant now) {
        return saleStartedAt == null ? 0 : Math.max(0,Duration.between(saleStartedAt,now).toMillis()/1000.0)*timeScale;
    }
    public boolean soldOut() { return availableSeats == 0 && heldSeats == 0; }
    public Phase phase(Instant now) {
        boolean ended = saleEndAt != null && !now.isBefore(saleEndAt);
        boolean reopened = lastReopenAt != null && now.isBefore(lastReopenAt.plus(Duration.ofSeconds(reopenWindowSec).dividedBy(timeScale)));
        return Phase.resolve(ended,hasHolds,everZero,reopened,availableSeats,heldSeats);
    }
    public Map<String,Object> fields(Instant now) {
        var fields = new LinkedHashMap<String,Object>();
        fields.put("availableSeats",availableSeats); fields.put("heldSeats",heldSeats);
        fields.put("pendingDepositSeats",pendingDepositSeats); fields.put("returnPendingSeats",returnPendingSeats);
        fields.put("soldSeats",soldSeats); fields.put("soldOut",soldOut()); fields.put("releaseAt",releaseAt);
        fields.put("saleEndAt",saleEndAt); fields.put("phase",phase(now));
        return fields;
    }
}
