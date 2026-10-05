package dev.endnjs.simulator.engine;

import java.time.Instant;

public interface RunTime {
    Instant instant();
    long nanoTime();
    void sleep(long millis) throws InterruptedException;
    static RunTime system() {
        return new RunTime() {
            public Instant instant() { return Instant.now(); }
            public long nanoTime() { return System.nanoTime(); }
            public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
        };
    }
}
