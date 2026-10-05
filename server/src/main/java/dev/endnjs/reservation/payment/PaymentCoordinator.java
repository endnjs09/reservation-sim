package dev.endnjs.reservation.payment;

import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/** Single-server coordination of approval and reconciliation, without holding database locks over HTTP. */
@Component
public class PaymentCoordinator {
    private final Object[] locks = IntStream.range(0, 1024).mapToObj(i -> new Object()).toArray();
    public Object lock(UUID orderId) { return locks[Math.floorMod(orderId.hashCode(), locks.length)]; }
}
