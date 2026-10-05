package dev.endnjs.reservation.hold;

import java.util.List;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;

public class SeatUnavailableException extends ApiException {
    private final List<Long> seatIds;
    public SeatUnavailableException(List<Long> seatIds) {
        super(ErrorCode.SEAT_UNAVAILABLE, "Seat unavailable");
        this.seatIds = seatIds.stream().distinct().sorted().toList();
    }
    public List<Long> unavailableSeatIds() { return seatIds; }
}
