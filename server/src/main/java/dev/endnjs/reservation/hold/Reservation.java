package dev.endnjs.reservation.hold;

import java.time.Instant;

public record Reservation(long id, long seatId, String userId, ReservationStatus status,
        Instant holdExpiresAt, String idempotencyKey, Instant confirmDeadline, dev.endnjs.reservation.payment.PaymentMethod paymentMethod,
        Instant depositDeadline, int seatCount) {}
