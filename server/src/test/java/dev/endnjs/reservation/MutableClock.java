package dev.endnjs.reservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;
    private final ZoneId zone;
    public MutableClock(Instant initial) { this(new AtomicReference<>(initial), ZoneOffset.UTC); }
    private MutableClock(AtomicReference<Instant> current, ZoneId zone) { this.current = current; this.zone = zone; }
    public void set(Instant instant) { current.set(instant); }
    public void advance(Duration duration) { current.updateAndGet(now -> now.plus(duration)); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { return new MutableClock(current, zone); }
    @Override public Instant instant() { return current.get(); }
}
