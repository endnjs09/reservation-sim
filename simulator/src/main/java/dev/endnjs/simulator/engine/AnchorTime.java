package dev.endnjs.simulator.engine;

import java.time.Instant;

/**
 * 3장 시계 기준점: 실행 시작 때 벽시계(anchorAt)와 단조 시계를 같은 순간에 한 번 잡고,
 * 그 뒤 엔진 안의 "현재 시각"은 anchorAt + 단조 경과로 계산한다. 이 환경의 벽시계는 단조 시계보다 빠르게 갈 수 있다.
 */
public final class AnchorTime implements RunTime {
    public record Anchor(Instant at, long nanos) {}
    private final RunTime base;
    private volatile Anchor anchor;

    public AnchorTime(RunTime base) { this.base = base; }

    /** 처음 호출 때 기준점을 잡고, 이후에는 같은 기준점을 돌려준다. */
    public synchronized Anchor anchor() {
        if (anchor == null) { Instant at = base.instant(); anchor = new Anchor(at, base.nanoTime()); }
        return anchor;
    }
    public Anchor current() { return anchor; }

    @Override public Instant instant() {
        Anchor current = anchor;
        return current == null ? base.instant() : current.at().plusNanos(base.nanoTime() - current.nanos());
    }
    @Override public long nanoTime() { return base.nanoTime(); }
    @Override public void sleep(long millis) throws InterruptedException { base.sleep(millis); }
}
