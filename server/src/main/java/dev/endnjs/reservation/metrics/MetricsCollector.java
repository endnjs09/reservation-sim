package dev.endnjs.reservation.metrics;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/** Memory-only recording; database sampling never runs on a request's transaction. */
@Component
public class MetricsCollector {
    private static final String[] COUNTERS = {"holdAttempts", "holdSuccess", "holdConflicts", "confirms",
            "declines", "notPayable", "expiredByScheduler", "recoveryAttempts", "recovered", "immediateReturns", "userCancels", "depositsRequested", "depositsPaid",
            "depositsExpired", "reopenCount", "reopenSeats", "acceptedInvalidKeys", "slotNotifyDropped"};
    private static final String[] ENDPOINTS = {"seats", "holds",
            "checkout", "confirm", "release", "reservation", "deposit", "depositPay", "cancel"};
    private final Clock clock;
    private final java.util.function.LongSupplier nanoTime;
    private volatile State state;
    public MetricsCollector(Clock clock) { this(clock,System::nanoTime); }
    @org.springframework.beans.factory.annotation.Autowired
    public MetricsCollector(Clock clock,java.util.function.LongSupplier nanoTime) {
        this.clock = clock;this.nanoTime=nanoTime;reset();
    }
    private static final class State {
        final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
        final Map<Long, LongAdder> conflicts = new ConcurrentHashMap<>();
        final Map<String, Window> endpoints = new LinkedHashMap<>();
        final Map<String, SchedulerRun> schedulers = new ConcurrentHashMap<>();
        final ArrayDeque<Event> recentEvents = new ArrayDeque<>();
        final ArrayDeque<Conflict> recentConflicts = new ArrayDeque<>();
        final Window pg = new Window();
        final RequestHistograms requests=new RequestHistograms(), pgHistograms=new RequestHistograms();
        volatile boolean zeroObserved;
        long windowStartNanos;
        State(long now) {
            windowStartNanos = now;
            for (String name : COUNTERS) counters.put(name, new LongAdder());
            for (String name : ENDPOINTS) { endpoints.put(name, new Window()); requests.register(name); }
            for(String name: new String[]{"confirm","query","cancel"}) pgHistograms.register(name);
            for (String name : new String[]{"expiry", "recovery", "depositExpiry", "reopen"}) schedulers.put(name, new SchedulerRun(null, 0));
        }
    }
    private record Event(String name, int count, Instant at) {}
    private record Conflict(long seatId, Instant at) {}
    public record Endpoint(double rps, int inflight, double avgMs, Map<String, Long> status,
            RequestHistograms.Latency latency,Map<String,Long> errors,Map<String,Long> errorClasses) {
        Endpoint(double rps,int inflight,double avgMs,Map<String,Long> status) { this(rps,inflight,avgMs,status,null,Map.of(),Map.of()); }
    }
    public record Pg(int confirmInflight, double confirmAvgMs, RequestHistograms.Latency confirm, Map<String,RequestHistograms.Endpoint> endpoints, Map<String,Object> cumulative, long timeouts) {}
    public record SchedulerRun(Instant lastRunAt, int count) {}
    public record WindowSnapshot(Map<String, Endpoint> endpoints, int inflightTotal, Pg pg,
            Map<String, SchedulerRun> schedulers, Map<String, Long> seatConflicts, boolean hasHolds, Map<String, Long> events, RequestHistograms.Total total, Map<String,Object> cumulative, int windowMs) {}
    private static final class Window {
        final AtomicInteger inflight = new AtomicInteger();
        long completed;
        long nanos;
        final Map<String, Long> statuses = new LinkedHashMap<>();
        synchronized void finish(long elapsed, int status) {
            completed++;
            nanos += elapsed;
            if (status > 0) statuses.merge(status >= 200 && status < 300 ? "2xx" : Integer.toString(status), 1L, Long::sum);
            inflight.decrementAndGet();
        }
        synchronized Endpoint drain(double seconds) {
            Endpoint result = new Endpoint(completed / seconds, inflight.get(),
                    completed == 0 ? 0 : nanos / 1_000_000.0 / completed, Map.copyOf(statuses));
            completed = 0; nanos = 0; statuses.clear();
            return result;
        }
    }
    /** A ticket retains its original window, so completion after reset cannot make the new inflight negative. */
    public static final class Measurement {
        private final Window window;
        private final long start = System.nanoTime();
        private final RequestHistograms.Ticket histogram;
        private final LongAdder acceptedInvalidKeys;
        private Measurement(Window window,RequestHistograms.Ticket histogram,LongAdder acceptedInvalidKeys) { this.window = window; this.histogram=histogram;this.acceptedInvalidKeys=acceptedInvalidKeys; window.inflight.incrementAndGet(); }
        public void acceptedInvalidKey() { acceptedInvalidKeys.increment(); }
        public void finish(int status) { finish(status,null); }
        public void finish(int status,String code) { long elapsed=Math.max(0,System.nanoTime()-start); window.finish(elapsed,status);histogram.finishElapsed(elapsed,status,code); }
    }
    public Measurement requestStarted(String endpoint) { var current=state;return new Measurement(current.endpoints.get(endpoint),current.requests.started(endpoint),current.counters.get("acceptedInvalidKeys")); }
    public Measurement pgStarted() { return pgStarted("confirm"); }
    public Measurement pgStarted(String endpoint) { var current=state;return new Measurement(current.pg,current.pgHistograms.started(endpoint),current.counters.get("acceptedInvalidKeys")); }
    public boolean zeroObserved() { return state.zeroObserved; }
    public void observeZeroAvailability() { state.zeroObserved = true; }
    public void schedulerFinished(String name, Instant at, int count) { state.schedulers.put(name, new SchedulerRun(at, count)); }
    public void increment(String counter) { state.counters.get(counter).increment(); }
    public void add(String counter, long count) { state.counters.get(counter).add(count); }
    public void event(String name,int count) {
        if (count<=0) return;
        var current=state;
        synchronized (current.recentEvents) {
            pruneEvents(current,clock.instant());
            current.recentEvents.addLast(new Event(name,count,clock.instant()));
        }
    }
    private static void pruneEvents(State current,Instant now) {
        Instant cutoff=now.minusSeconds(1);
        while (!current.recentEvents.isEmpty() && !current.recentEvents.getFirst().at().isAfter(cutoff)) current.recentEvents.removeFirst();
    }
    public void conflict(long seatId) {
        State current = state;
        current.counters.get("holdConflicts").increment();
        current.conflicts.computeIfAbsent(seatId, ignored -> new LongAdder()).increment();
        synchronized (current.recentConflicts) {
            Instant now = clock.instant();
            prune(current, now);
            current.recentConflicts.addLast(new Conflict(seatId, now));
        }
    }
    public long seatConflictCount(long seatId) {
        LongAdder count = state.conflicts.get(seatId);
        return count == null ? 0 : count.sum();
    }
    public Map<String, Long> counters() {
        State current = state;
        Map<String, Long> snapshot = new LinkedHashMap<>();
        for (String name : COUNTERS) snapshot.put(name, current.counters.get(name).sum());
        return snapshot;
    }
    public synchronized WindowSnapshot sample(Instant now) {
        State current = state;
        long sampledNanos=nanoTime.getAsLong();
        double seconds = Math.max(0.001, (sampledNanos-current.windowStartNanos) / 1_000_000_000.0);
        current.windowStartNanos = sampledNanos;
        Map<String, Endpoint> endpoints = new LinkedHashMap<>();
        current.endpoints.forEach((name, window) -> endpoints.put(name, window.drain(seconds)));
        Endpoint pg = current.pg.drain(seconds);
        var histograms=current.requests.sample(seconds);var pgHist=current.pgHistograms.sample(seconds);
        endpoints.replaceAll((name,value) -> {
            var h=histograms.endpoints().get(name);
            return new Endpoint(value.rps(),value.inflight(),value.avgMs(),value.status(),h.latency(),h.errors(),h.errorClasses());
        });
        Map<String, Long> conflicts = new LinkedHashMap<>();
        synchronized (current.recentConflicts) {
            prune(current, now);
            current.recentConflicts.forEach(event -> conflicts.merge(Long.toString(event.seatId()), 1L, Long::sum));
        }
        Map<String,Long> events=new LinkedHashMap<>(Map.of("REOPEN",0L,"USER_CANCEL",0L,"DEPOSIT_EXPIRED",0L));
        synchronized (current.recentEvents) {
            pruneEvents(current,now);
            current.recentEvents.forEach(event -> events.merge(event.name(),(long)event.count(),Long::sum));
        }
        var cumulative=new java.util.LinkedHashMap<>(histograms.cumulative());
        return new WindowSnapshot(Map.copyOf(endpoints), histograms.endpoints().values().stream().mapToInt(RequestHistograms.Endpoint::inflight).sum(),
                new Pg(pgHist.endpoints().get("confirm").inflight(),pgHist.endpoints().get("confirm").avgMs(),pgHist.endpoints().get("confirm").latency(),pgHist.endpoints(),pgHist.cumulative(),pgHist.endpoints().values().stream().mapToLong(e -> e.errorClasses().getOrDefault("timeout",0L)).sum()), Map.copyOf(current.schedulers), Map.copyOf(conflicts),
                current.counters.get("holdSuccess").sum() > 0, Map.copyOf(events),histograms.total(),java.util.Collections.unmodifiableMap(cumulative),(int)Math.min(Integer.MAX_VALUE,Math.round(seconds*1000)));
    }
    private static void prune(State current, Instant now) {
        Instant cutoff = now.minusSeconds(2);
        while (!current.recentConflicts.isEmpty() && !current.recentConflicts.getFirst().at().isAfter(cutoff)) {
            current.recentConflicts.removeFirst();
        }
    }
    public synchronized void reset() { state = new State(nanoTime.getAsLong()); }
}
