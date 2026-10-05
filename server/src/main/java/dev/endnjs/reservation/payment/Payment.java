package dev.endnjs.reservation.payment;

import java.util.UUID;

public record Payment(UUID id, long reservationId, int amount, PaymentStatus status, String paymentKey) {}
