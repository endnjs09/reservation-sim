package dev.endnjs.reservation.hold;

import java.time.Instant;
import java.util.List;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;
import dev.endnjs.reservation.seat.SeatRepository;
import org.springframework.stereotype.Component;

@Component
public class ConditionalHoldStrategy implements HoldStrategy {
    private final SeatRepository seats;
    public ConditionalHoldStrategy(SeatRepository seats) { this.seats = seats; }
    public String name() { return "conditional"; }
    public void acquire(long seatId, Instant now) {
        if (!seats.conditionalHold(seatId, now)) throw new SeatUnavailableException(List.of(seatId));
    }
    public void acquire(List<Long> seatIds, Instant now) {
        if (seatIds.size() == 1) { acquire(seatIds.getFirst(), now); return; }
        var updated = seats.conditionalHold(seatIds, now);
        if (updated.size() != seatIds.size()) {
            throw new SeatUnavailableException(seatIds.stream().filter(id -> !updated.contains(id)).toList());
        }
    }
}
