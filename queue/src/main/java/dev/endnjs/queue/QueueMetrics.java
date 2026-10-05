package dev.endnjs.queue;

import java.lang.management.ManagementFactory;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QueueMetrics {
    private final QueueState state;private volatile RequestHistograms requests;private volatile Map<String,Object> latest;
    private long previousGc,lastNanos;
    public QueueMetrics(QueueState state) { this.state=state;reset(); }
    public synchronized void reset() {
        requests=new RequestHistograms();for(String key:List.of("queue.enter","queue.status","queue.leave","internal.events")) requests.register(key);
        previousGc=gc();lastNanos=System.nanoTime();sample();
    }
    RequestHistograms.Ticket start(String endpoint) { return requests.started(endpoint); }
    @Scheduled(fixedRateString="${queue.admission-interval-ms:1000}") public void admit() { state.tick(); }
    @Scheduled(fixedRate=1000) public synchronized void sample() {
        long start=System.nanoTime();double seconds=Math.max(.001,(start-lastNanos)/1_000_000_000.0);lastNanos=start;
        var histogram=requests.sample(seconds);var stats=state.stats();var counts=(Map<?,?>)stats.get("counters");
        var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();long gc=gc();
        var result=QueueState.fields("at",state.now(),"windowMs",Math.round(seconds*1000),"endpoints",histogram.endpoints(),"total",histogram.total(),"cumulative",histogram.cumulative(),
                "waiting",stats.get("WAITING"),"active",stats.get("ADMITTED"),"busy",stats.get("busy"),"slotsBusyOverTtl",stats.get("slotsBusyOverTtl"),
                "expiredByBusyCap",counts.get("expiredByBusyCap"),"admittedThisTick",state.admittedThisTick(),"keysIssued",counts.get("keysIssued"),
                "jvm",Map.of("heapUsedMb",Math.round(heap.getUsed()/104857.6)/10.0,"gcPauseMs",Math.max(0,gc-previousGc)),"metricsSelfMs",Math.round((System.nanoTime()-start)/100000.0)/10.0);
        previousGc=gc;latest=Collections.unmodifiableMap(result);
    }
    public Map<String,Object> snapshot() { return latest; }
    private static long gc() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0,bean.getCollectionTime())).sum(); }
}
