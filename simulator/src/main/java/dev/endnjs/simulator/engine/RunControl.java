package dev.endnjs.simulator.engine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

final class RunControl {
    private final RunTime time;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile long start;
    private volatile long limit;
    private long requestTimeoutMs=10000;
    void requestTimeoutMs(long value) { requestTimeoutMs=value; }
    RunControl(RunTime time) { this.time = time; }
    void start(int seconds) { start(seconds,1); }
    void start(int simulationSeconds,int scale) { start=time.nanoTime();limit=Duration.ofSeconds(simulationSeconds).dividedBy(scale).toNanos(); }
    void stop() { stopped.set(true); }
    boolean stopped() { return stopped.get() || time.nanoTime() - start >= limit; }
    void check() throws InterruptedException {
        if (stopped() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Run stopped");
    }
    Duration requestTimeout() throws InterruptedException {
        check();
        long remaining = limit - (time.nanoTime() - start);
        if (remaining <= 0) throw new InterruptedException("Run deadline reached");
        return Duration.ofNanos(Math.min(Duration.ofMillis(requestTimeoutMs).toNanos(), remaining));
    }
    void pause(long millis) throws InterruptedException {
        check();
        long remaining = Math.max(1, (limit - (time.nanoTime() - start) + 999_999) / 1_000_000);
        time.sleep(Math.min(millis, remaining));
        check();
    }
    void arrive(long offsetMillis) throws InterruptedException {
        long elapsed = Math.max(0, (time.nanoTime() - start) / 1_000_000);
        pause(Math.max(0, offsetMillis - elapsed));
    }
}
