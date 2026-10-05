package dev.endnjs.queue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 3장 시계 기준점: reset 전에는 시스템 시계, reset에서 anchorAt을 받은 뒤에는 anchorAt + 단조 시계 경과.
 * 이 환경(WSL)의 벽시계는 단조 시계보다 빠르게 가므로 판단용 현재 시각을 벽시계에서 직접 읽지 않는다.
 * server·mock-pg 모듈에 같은 코드가 있다 (모듈끼리 의존하지 않는다).
 */
public final class AnchoredClock extends Clock {
    private record Anchor(Instant at, long nanos) {}
    private final Clock system;
    private final LongSupplier nanoTime;
    private final AtomicReference<Anchor> anchor = new AtomicReference<>();

    public AnchoredClock() { this(Clock.systemUTC(), System::nanoTime); }
    public AnchoredClock(Clock system, LongSupplier nanoTime) { this.system = system; this.nanoTime = nanoTime; }

    /** receivedNanos: reset 요청을 받은 순간의 nanoTime (처리 시간을 기준점에 넣지 않기 위해 호출 쪽에서 먼저 잡는다). */
    public void anchor(Instant anchorAt, long receivedNanos) { anchor.set(new Anchor(anchorAt, receivedNanos)); }
    public long nanoTime() { return nanoTime.getAsLong(); }
    public Instant anchorAt() { var current = anchor.get(); return current == null ? null : current.at(); }

    @Override public Instant instant() {
        var current = anchor.get();
        return current == null ? system.instant() : current.at().plusNanos(nanoTime.getAsLong() - current.nanos());
    }
    @Override public long millis() { return instant().toEpochMilli(); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) {
        if (ZoneOffset.UTC.equals(zone)) return this;
        throw new UnsupportedOperationException("AnchoredClock is UTC only");
    }
}
