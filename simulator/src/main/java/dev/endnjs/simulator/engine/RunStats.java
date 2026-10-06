package dev.endnjs.simulator.engine;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

public final class RunStats {
    public enum UserState { arriving, waiting, admitted_browsing, holding, authenticating, confirming, pending_deposit, cancel_wait, departed, done }
    public enum Outcome { confirmed, soldOut, gaveUp, abandoned, incomplete, error }
    public enum Milestone { depositPaid, depositExpired, canceledAfterPurchase, revisited, queueAbandoned }
    private static final String[] EVENTS = {"conflicts", "refreshes", "requeues", "authFailed", "declined", "holdExpired", "revisits", "immediateReturnsSeen", "reopenSeen", "saleEndFallback", "cancelPendingAtStop", "queueAbandons", "queueAbandonsStalled", "rateLimited", "revisitRetries", "revisitRetryAdmitted"};
    private static final String[] ENDPOINTS = {"server.reset", "pg.reset", "pg.config", "queue.reset", "queue.enter", "queue.status",
            "queue.leave", "seats", "holds", "release", "checkout", "auth", "confirm", "reservation", "deposit", "deposit.pay", "reservation.cancel"};
    private final RunConfig config;
    private final RunTime time;
    private final List<Persona> personas;
    private final List<ChurnPolicy.Type> churns;
    private final java.util.concurrent.atomic.AtomicIntegerArray milestones;
    private record Inventory(Long sold,Map<String,Long> byGrade) {}
    private volatile Inventory inventory=new Inventory(null,Map.of());
    private long returnsSeen,reopensSeen;
    private final AtomicReferenceArray<UserState> states;
    private final AtomicReferenceArray<Outcome> results;
    private final AtomicReferenceArray<Boolean> refreshing;
    private final Map<String, Counter> requests = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> responses = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> events = new ConcurrentHashMap<>();
    private final ArrayDeque<Recent> recent = new ArrayDeque<>();
    /** 실시간 이벤트 목록용: 실행이 끝나도·화면을 새로 열어도 마지막 80건을 보여 줄 수 있게 (1초 창과 별도). */
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private volatile Instant startedAt;
    private volatile long startNanos;
    private long windowNanos,metricsNanos;
    private final RequestHistograms histograms=new RequestHistograms();
    private final LongAdder serverSent=new LongAdder(),serverFailed=new LongAdder(),windowServerSent=new LongAdder(),windowServerFailed=new LongAdder();
    public record ClientMetrics(RequestHistograms.Sample histogram,long serverRequests,long serverFailures,long serverSent,long serverFailed) {}
    private volatile ClientMetrics clientMetrics=new ClientMetrics(histograms.sample(1),0,0,0,0);
    public ClientMetrics clientMetrics() { return clientMetrics; }
    private synchronized void sampleMetrics() {
        long now=time.nanoTime();double seconds=Math.max(.001,(now-metricsNanos)/1_000_000_000.0);metricsNanos=now;
        clientMetrics=new ClientMetrics(histograms.sample(seconds),windowServerSent.sumThenReset(),windowServerFailed.sumThenReset(),serverSent.sum(),serverFailed.sum());
    }
    private record Recent(long atNanos, String text) {}
    private static final class Counter {
        final LongAdder sent = new LongAdder();
        final LongAdder latencyNanos = new LongAdder();
        final LongAdder received = new LongAdder();
        final AtomicInteger inflight = new AtomicInteger();
        long windowSent;
        synchronized void start() { sent.increment(); inflight.incrementAndGet(); windowSent++; }
        synchronized long drain() { long value = windowSent; windowSent = 0; return value; }
    }
    public RunStats(RunConfig config, RunTime time, List<Persona> personas) {
        this(config,time,personas,java.util.stream.IntStream.range(0,config.users()).mapToObj(i -> VirtualUser.profile(config,i).churn()).toList());
    }
    RunStats(RunConfig config,RunTime time,List<Persona> personas,List<ChurnPolicy.Type> churns) {
        this.config=config;this.time=time;this.personas=List.copyOf(personas);this.churns=List.copyOf(churns);
        milestones=new java.util.concurrent.atomic.AtomicIntegerArray(config.users());
        states = new AtomicReferenceArray<>(config.users()); results = new AtomicReferenceArray<>(config.users());
        refreshing = new AtomicReferenceArray<>(config.users());
        for (int i = 0; i < config.users(); i++) states.set(i, UserState.arriving);
        for (String name : ENDPOINTS) { requests.put(name,new Counter());if(!java.util.Set.of("server.reset","pg.reset","pg.config","queue.reset").contains(name)) histograms.register(name); }
        for (String name : List.of("2xx", "4xx", "5xx", "transportErrors")) responses.put(name, new LongAdder());
        for (String name : EVENTS) events.put(name, new LongAdder());
        start();
    }
    synchronized void start() { start(time.instant(), time.nanoTime()); }
    /** 3장: 실행 기준점(anchorAt, 그 순간의 nanoTime)에서 startedAt과 t를 함께 잡는다. */
    synchronized void start(Instant at, long nanos) { startedAt = at; startNanos = nanos; windowNanos = startNanos;metricsNanos=startNanos; }
    public Instant startedAt() { return startedAt; }
    public void state(int user, UserState state) { states.set(user, state); }
    public void refreshing(int user, boolean value) { refreshing.set(user, value); }
    public void finish(int user, Outcome outcome) {
        // 예매를 마치고 취소 시점만 기다리던 사용자가 실행 중지로 끝나면: 실제 예매는 끝났으므로 confirmed,
        // 실행되지 않은 취소는 events.cancelPendingAtStop (docs/DECISION_CLAUDE.md)
        if (outcome == Outcome.incomplete && states.get(user) == UserState.cancel_wait) {
            if (results.compareAndSet(user, null, Outcome.confirmed)) { events.get("cancelPendingAtStop").increment(); states.set(user, UserState.done); }
            return;
        }
        if (results.compareAndSet(user, null, outcome)) states.set(user, UserState.done);
    }
    public void milestone(int user,Milestone value) { milestones.getAndUpdate(user,bits -> bits | 1<<value.ordinal()); }
    public void finishPending(Outcome outcome) { for (int i = 0; i < config.users(); i++) finish(i, outcome); }
    public final class RequestMeasurement {
        private final Counter counter;
        private final boolean reservationTarget;
        private final RequestHistograms.Ticket ticket;
        private final long start = time.nanoTime();
        private RequestMeasurement(String endpoint,HttpTransport.Target target) {
            this.counter=requests.get(endpoint);counter.start();reservationTarget=target==HttpTransport.Target.SERVER;
            ticket=histograms.started(endpoint);
            if(reservationTarget) serverSent.increment();
        }
        void response(int status) { response(status,null); }
        void response(int status,String code) {
            ticket.finishElapsed(time.nanoTime()-start,status,code);
            if(status==429 && "RATE_LIMITED".equals(code)) {
                // 좌석 조회 새로고침 제한: 실패율(failPct)의 분자·분모 모두에서 뺀다. 건수는 events.rateLimited (docs/DECISION_CLAUDE.md)
                if(reservationTarget) serverSent.decrement();
                events.get("rateLimited").increment();
                responses.computeIfAbsent("4xx",ignored -> new LongAdder()).increment();
                counter.received.increment(); counter.latencyNanos.add(Math.max(0, time.nanoTime() - start));counter.inflight.decrementAndGet();
                return;
            }
            if(reservationTarget) windowServerSent.increment();
            if(reservationTarget && status>=500) { serverFailed.increment();windowServerFailed.increment(); }
            counter.received.increment(); counter.latencyNanos.add(Math.max(0, time.nanoTime() - start));
            responses.computeIfAbsent(status / 100 + "xx", ignored -> new LongAdder()).increment();
            counter.inflight.decrementAndGet();
        }
        void failed(Throwable failure) {
            if(reservationTarget) windowServerSent.increment();
            String type=failure instanceof InterruptedException ? null : timeout(failure) ? "timeout" : "transport";
            responses.get("transportErrors").increment();
            if(type!=null) { if(reservationTarget) { serverFailed.increment();windowServerFailed.increment(); } }
            ticket.finishElapsed(time.nanoTime()-start,0,type);counter.inflight.decrementAndGet();
        }
    }
    RequestMeasurement request(String endpoint,HttpTransport.Target target) { return new RequestMeasurement(endpoint,target); }
    static boolean timeout(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) return true;
        return false;
    }
    public void event(String name, String user, String message) {
        events.get(name).increment();
        if (!name.equals("refreshes")) note(user, message);
    }
    /** 사건 수만 센다 (실시간 이벤트 목록에 남기지 않음: 자주 일어나는 재시도 등). */
    public void count(String name) { events.get(name).increment(); }
    public void note(String user, String message) {
        long now = time.nanoTime();
        synchronized (recent) {
            pruneRecent(now);
            if (recent.size() == 20) recent.removeFirst();
            long tenths = Math.max(0, now - startNanos) / 100_000_000;
            String line="%02d:%02d.%d %s %s".formatted(tenths / 600, tenths / 10 % 60, tenths % 10, user, message);
            recent.addLast(new Recent(now, line));
            if (history.size() == 80) history.removeFirst();
            history.addLast(line);
        }
    }
    private void pruneRecent(long now) { while (!recent.isEmpty() && now - recent.getFirst().atNanos() >= 1_000_000_000) recent.removeFirst(); }
    private Map<String, Long> totals(Map<String, LongAdder> source) {
        var values = new LinkedHashMap<String, Long>(); source.forEach((key, value) -> values.put(key, value.sum())); return Map.copyOf(values);
    }
    private static Map<String, Long> emptyOutcomes() {
        var outcomes = new LinkedHashMap<String, Long>();
        for (Outcome outcome : Outcome.values()) outcomes.put(outcome.name(), 0L);
        for (Milestone milestone : Milestone.values()) outcomes.put(milestone.name(),0L);
        return outcomes;
    }
    private Map<String, Long> outcomes() {
        var counts = emptyOutcomes();
        for (int i=0;i<config.users();i++) addUser(counts,i);
        return Map.copyOf(counts);
    }
    private void addUser(Map<String,Long> counts,int user) {
        var value=results.get(user);if(value!=null) counts.merge(value.name(),1L,Long::sum);
        int flags=milestones.get(user);
        for(var milestone:Milestone.values()) if((flags & 1<<milestone.ordinal())!=0) counts.merge(milestone.name(),1L,Long::sum);
    }
    private Map<String,Map<String,Long>> byChurn() {
        var values=new LinkedHashMap<String,Map<String,Long>>();
        for(var type:ChurnPolicy.Type.values()) values.put(type.name(),emptyOutcomes());
        for(int i=0;i<config.users();i++) addUser(values.get(churns.get(i).name()),i);
        values.replaceAll((name,counts) -> Map.copyOf(counts));return Map.copyOf(values);
    }
    /** Only service-internal, actual admin snapshots are used for inventory and return observations. */
    synchronized void observeAdminStats(Map<String,Object> snapshot) {
        if(snapshot.get("seats") instanceof Map<?,?> seats && seats.get("SOLD") instanceof Number sold
                && seats.get("byGrade") instanceof Map<?,?> grades) {
            var counts=new LinkedHashMap<String,Long>();
            grades.forEach((name,states) -> {
                if(name instanceof String grade && states instanceof Map<?,?> values && values.get("SOLD") instanceof Number n) counts.put(grade,n.longValue());
            });
            inventory=new Inventory(sold.longValue(),Map.copyOf(counts));
        }
        if(snapshot.get("counters") instanceof Map<?,?> counters) {
            long immediate=counters.get("immediateReturns") instanceof Number n ? n.longValue() : returnsSeen;
            long reopen=counters.get("reopenCount") instanceof Number n ? n.longValue() : reopensSeen;
            if(immediate>returnsSeen) { events.get("immediateReturnsSeen").add(immediate-returnsSeen);note("server","좌석 즉시 반환 +"+(immediate-returnsSeen)); }
            if(reopen>reopensSeen) { events.get("reopenSeen").add(reopen-reopensSeen);note("server","취소표 일괄 오픈 +"+(reopen-reopensSeen)); }
            returnsSeen=immediate;reopensSeen=reopen;
        }
    }
    void finalInventoryUnavailable() { inventory=new Inventory(null,Map.of()); }
    public synchronized Summary summary() {
        sampleMetrics();
        var sent = new LinkedHashMap<String, Long>(); var averages = new LinkedHashMap<String, Double>();
        requests.forEach((name, counter) -> {
            sent.put(name, counter.sent.sum());
            averages.put(name, counter.received.sum() == 0 ? 0 : counter.latencyNanos.sum() / 1_000_000.0 / counter.received.sum());
        });
        var byPersona = new LinkedHashMap<String, Map<String, Long>>();
        for (Persona persona : Persona.values()) byPersona.put(persona.key(), emptyOutcomes());
        for (int i = 0; i < config.users(); i++) {
            addUser(byPersona.get(personas.get(i).key()),i);
        }
        byPersona.replaceAll((name, counts) -> Map.copyOf(counts));
        var observed=inventory;
        return new Summary(startedAt, Math.max(0, time.nanoTime() - startNanos) / 1_000_000, config,
                new Summary.Requests(sent.values().stream().mapToLong(Long::longValue).sum(), Map.copyOf(sent)),
                totals(responses), Map.copyOf(averages), outcomes(), Map.copyOf(byPersona), totals(events),byChurn(),observed.sold(),observed.byGrade()).withMetrics(Map.of(),Map.of(),clientMetrics.histogram().cumulative(),cumulativeErrorClasses());
    }
    private Map<String,Long> cumulativeErrorClasses() {
        var classes=RequestHistograms.zeros();Object total=clientMetrics.histogram().cumulative().get("total");
        if(total instanceof Map<?,?> map && map.get("errorClasses") instanceof Map<?,?> errors) errors.forEach((k,v) -> classes.put(k.toString(),((Number)v).longValue()));return Map.copyOf(classes);
    }
    public record Live(long elapsedMs, boolean running, Map<String, Long> users, Map<String, Long> outcomes,
            Map<String, Long> events, Map<String, Double> clientRps, Map<String, Long> pg, List<String> recentEvents,
            long refreshing,Map<String,Map<String,Long>> outcomesByChurn,
            Map<String,Map<String,Long>> usersByChurn,Long seatsSold,Map<String,Long> seatsByGrade,Map<String,Long> userGroups,List<String> eventHistory) {}
    /** 실시간 화면 사용자 노드의 칸. 사용자마다 정확히 한 칸: 합 = users (docs/DECISION_CLAUDE.md). */
    static String userGroup(UserState state,Outcome result) {
        return switch(state) {
            case arriving -> "arriving";
            case waiting -> "waiting";
            case admitted_browsing,holding,authenticating,confirming,pending_deposit -> "inside";
            case departed -> "revisitWait";
            case cancel_wait -> "bought"; // 예매(입금) 완료 뒤 취소 시점을 기다리는 중
            case done -> result==Outcome.confirmed ? "bought" : result==Outcome.incomplete || result==Outcome.error ? "stopped" : "left";
        };
    }
    private Map<String,Long> userGroups() {
        var groups=new LinkedHashMap<String,Long>();
        for(String key:List.of("arriving","waiting","inside","revisitWait","left","bought","stopped")) groups.put(key,0L);
        for(int i=0;i<config.users();i++) {
            Outcome result=results.get(i);UserState state=states.get(i);
            // finish()는 결과를 먼저 쓰고 상태를 done으로 바꾼다: 그 사이에 읽혀도 결과 기준으로 센다
            groups.merge(userGroup(result!=null ? UserState.done : state,result),1L,Long::sum);
        }
        return Map.copyOf(groups);
    }
    /** Called once per UI tick; cumulative summary values are never drained. */
    public synchronized Live sampleLive(boolean running) {
        sampleMetrics();
        long now = time.nanoTime();
        double seconds = Math.max(.001, (now - windowNanos) / 1_000_000_000.0); windowNanos = now;
        var rates = new LinkedHashMap<String, Double>(); requests.forEach((name, counter) -> rates.put(name, counter.drain() / seconds));
        var users = new LinkedHashMap<String, Long>(); for (UserState state : UserState.values()) users.put(state.name(), 0L);
        for (int i = 0; i < config.users(); i++) users.merge(states.get(i).name(), 1L, Long::sum);
        long refreshCount = 0;
        for (int i = 0; i < config.users(); i++) if (Boolean.TRUE.equals(refreshing.get(i))) refreshCount++;
        List<String> messages;
        List<String> eventHistory;
        synchronized (recent) { pruneRecent(now); messages = recent.stream().map(Recent::text).toList(); eventHistory = List.copyOf(history); }
        var usersByChurn=new LinkedHashMap<String,Map<String,Long>>();
        for(var type:ChurnPolicy.Type.values()) {
            var counts=new LinkedHashMap<String,Long>();for(var state:UserState.values()) counts.put(state.name(),0L);
            usersByChurn.put(type.name(),counts);
        }
        for(int i=0;i<config.users();i++) usersByChurn.get(churns.get(i).name()).merge(states.get(i).name(),1L,Long::sum);
        usersByChurn.replaceAll((name,counts) -> Map.copyOf(counts));var observed=inventory;
        return new Live(Math.max(0, now - startNanos) / 1_000_000, running, Map.copyOf(users), outcomes(), totals(events),
                Map.copyOf(rates), Map.of("authInflight", users.get("authenticating"), "confirmInflight", (long) requests.get("confirm").inflight.get(),
                        "failed", events.get("authFailed").sum() + events.get("declined").sum()), messages, refreshCount,byChurn(),Map.copyOf(usersByChurn),observed.sold(),observed.byGrade(),userGroups(),eventHistory);
    }
}
