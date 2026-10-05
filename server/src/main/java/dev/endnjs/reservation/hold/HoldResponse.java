package dev.endnjs.reservation.hold;

import java.time.Instant;
import dev.endnjs.reservation.seat.Grade;
import dev.endnjs.reservation.seat.SeatView;
import java.util.List;

public record HoldResponse(long reservationId, long seatId, Grade grade, int price,
        ReservationStatus status, Instant holdExpiresAt, List<SeatView> seats, int totalPrice) {}
