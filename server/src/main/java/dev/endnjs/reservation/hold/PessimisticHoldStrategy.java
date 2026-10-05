package dev.endnjs.reservation.hold;

import java.time.Instant;
import java.util.List;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;
import dev.endnjs.reservation.seat.SeatRepository;
import dev.endnjs.reservation.seat.SeatStatus;
import org.springframework.stereotype.Component;

@Component
public class PessimisticHoldStrategy implements HoldStrategy {
    private final SeatRepository seats;
    public PessimisticHoldStrategy(SeatRepository seats) { this.seats = seats; }
    public String name() { return "pessimistic"; }
    public void acquire(long seatId, Instant now) {
        var seat = seats.lock(seatId).orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_FOUND, "Seat not found"));
        if (seat.status() != SeatStatus.AVAILABLE) throw new SeatUnavailableException(List.of(seatId));
        seats.unconditionalHold(seatId, now);
    }
    public void acquire(List<Long> seatIds, Instant now) {
        if (seatIds.size() == 1) { acquire(seatIds.getFirst(), now); return; }
        var locked = seats.lock(seatIds);
        var unavailable = locked.stream().filter(s -> s.status() != SeatStatus.AVAILABLE).map(s -> s.id()).toList();
        if (!unavailable.isEmpty()) throw new SeatUnavailableException(unavailable);
        for (long seatId : seatIds) seats.unconditionalHold(seatId, now);
    }
}
