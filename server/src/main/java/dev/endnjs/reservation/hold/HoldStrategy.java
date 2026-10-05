package dev.endnjs.reservation.hold;

import java.time.Instant;
import java.util.List;

public interface HoldStrategy {
    String name();
    void acquire(long seatId, Instant now);
    default void acquire(List<Long> seatIds, Instant now) {
        for (long seatId : seatIds) acquire(seatId, now);
    }
}
