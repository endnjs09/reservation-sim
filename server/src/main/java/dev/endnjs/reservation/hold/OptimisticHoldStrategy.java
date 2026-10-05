package dev.endnjs.reservation.hold;

import java.time.Instant;
import java.util.List;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import jakarta.persistence.EntityManager;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;
import dev.endnjs.reservation.seat.Seat;
import dev.endnjs.reservation.seat.SeatStatus;
import org.springframework.stereotype.Component;

@Component
public class OptimisticHoldStrategy implements HoldStrategy {
    private final EntityManager entities;
    public OptimisticHoldStrategy(EntityManager entities) { this.entities = entities; }
    public String name() { return "optimistic"; }
    public void acquire(long seatId, Instant now) {
        Seat seat = entities.find(Seat.class, seatId);
        if (seat == null) throw new ApiException(ErrorCode.SEAT_NOT_FOUND, "Seat not found");
        if (seat.status() != SeatStatus.AVAILABLE) throw new SeatUnavailableException(List.of(seatId));
        seat.hold(now);
        try { entities.flush(); }
        catch (OptimisticLockException | OptimisticLockingFailureException conflict) {
            throw new SeatUnavailableException(List.of(seatId));
        }
    }
    public void acquire(List<Long> seatIds, Instant now) {
        if (seatIds.size() == 1) { acquire(seatIds.getFirst(), now); return; }
        var requested = seatIds.stream().map(id -> entities.find(Seat.class, id)).toList();
        var unavailable = java.util.stream.IntStream.range(0, requested.size())
                .filter(i -> requested.get(i).status() != SeatStatus.AVAILABLE).mapToObj(seatIds::get).toList();
        if (!unavailable.isEmpty()) throw new SeatUnavailableException(unavailable);
        for (int i = 0; i < requested.size(); i++) {
            requested.get(i).hold(now);
            try { entities.flush(); }
            catch (OptimisticLockException | OptimisticLockingFailureException conflict) {
                throw new SeatUnavailableException(List.of(seatIds.get(i)));
            }
        }
    }
}
