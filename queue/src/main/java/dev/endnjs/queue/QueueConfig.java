package dev.endnjs.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public record QueueConfig(int maxActive,int admitPerSec,int admissionTtlSec,int busyMaxExtraSec,
        int timeScale,Instant saleEndAt,boolean closeQueueOnSoldOut,String runEpoch,Instant anchorAt) {
    public QueueConfig {
        if(maxActive<=0 || admitPerSec<=0 || admissionTtlSec<=0 || busyMaxExtraSec<=0
                || !Set.of(1,2,4).contains(timeScale) || saleEndAt==null || runEpoch==null || runEpoch.isBlank()) {
            throw new IllegalArgumentException("Invalid queue configuration");
        }
    }
    public Duration realDuration(int seconds) { return Duration.ofSeconds(seconds).dividedBy(timeScale); }
}
