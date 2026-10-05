package dev.endnjs.reservation.config;

import org.springframework.stereotype.Component;

@Component
public class RuntimeConfigStore {
    private volatile RuntimeConfig current;
    public RuntimeConfigStore(ReservationProperties properties) { current = RuntimeConfig.from(properties); }
    public RuntimeConfig current() { return current; }
    public void replace(RuntimeConfig config) { current = config; }
}
