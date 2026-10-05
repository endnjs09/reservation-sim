package dev.endnjs.simulator.engine;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/** One shared internal lifecycle poll, at most once per real second; no user request measurements. */
final class RevisitSchedule {
    record Signal(Instant releaseAt,Instant saleEndAt) {}
    private final RunHttp http;
    private final RunTime time;
    private final RunConfig config;
    private final RunStats stats;
    private long lastPoll,reopenCount;
    private final java.util.Set<Instant> reopenedBatches=new java.util.HashSet<>();
    private boolean polled,backgroundWork;
    private Signal signal;
    RevisitSchedule(RunHttp http,RunTime time,RunConfig config) { this(http,time,config,null); }
    RevisitSchedule(RunHttp http,RunTime time,RunConfig config,RunStats stats) {
        this.http=http;this.time=time;this.config=config;this.stats=stats;
    }
    private Instant sharedSaleEndAt;
    synchronized void start(Instant resetAt) { start(resetAt,null); }
    /** sharedSaleEndAt is the value the engine sent to both servers at reset (spec 8.2). */
    synchronized void start(Instant resetAt,Instant sharedSaleEndAt) {
        this.sharedSaleEndAt=sharedSaleEndAt;signal=new Signal(null,resetAt.plus(config.realDuration(config.saleDurationSec())));
    }
    synchronized boolean ended() { return signal!=null && !time.instant().isBefore(signal.saleEndAt()); }
    synchronized boolean saleClosed() { return sharedSaleEndAt!=null && !time.instant().isBefore(sharedSaleEndAt); }
    synchronized Signal signal() throws InterruptedException {
        if(!polled || time.nanoTime()-lastPoll>=1_000_000_000L) refresh();
        return signal;
    }
    synchronized boolean visitable(Instant release) { return release.equals(signal.releaseAt()) || reopenedBatches.contains(release); }
    synchronized boolean backgroundWork() { return backgroundWork; }
    synchronized boolean refresh() throws InterruptedException {
        polled=true;lastPoll=time.nanoTime();
        try {
            var response=http.internalStats();
            if(!response.ok()) return false;
            Object batches=response.body().get("releaseBatches");
            Instant release=batches instanceof Map<?,?> values ? instant(values.get("nextReleaseAt")) : null;
            Instant end=instant(response.body().get("saleEndAt"));
            if(response.body().get("counters") instanceof Map<?,?> counters) {
                long next=count(counters,"reopenCount");
                if(next>reopenCount && signal.releaseAt()!=null) reopenedBatches.add(signal.releaseAt());
                reopenCount=next;
            }
            signal=new Signal(release,end==null ? signal.saleEndAt() : end);
            if(response.body().get("reservations") instanceof Map<?,?> reservations && batches instanceof Map<?,?> values)
                backgroundWork=count(reservations,"HELD")+count(reservations,"CONFIRMING")+count(reservations,"PENDING_DEPOSIT")+count(values,"open")>0;
            if(stats!=null) stats.observeAdminStats(response.body());
            return true;
        } catch(IOException | RuntimeException transientFailure) { return false; }
    }
    private static long count(Map<?,?> values,String key) { return values.get(key) instanceof Number n ? n.longValue() : 0; }
    private static Instant instant(Object value) { return value instanceof String text ? Instant.parse(text) : null; }
}
