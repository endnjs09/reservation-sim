package dev.endnjs.simulator.service;

import dev.endnjs.simulator.engine.RunStats;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class StreamService {
    private final RunService runs;
    private final ServerMetricsPoller poller;
    private final Set<Subscription> subscribers = ConcurrentHashMap.newKeySet();
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    private String metricsRunId;
    private Map<String, Object> runServer = Map.of(), runPg = Map.of(),runQueue=Map.of(),runQueueStats=Map.of();
    private java.time.Instant serverAt, pgAt,queueAt;
    private boolean finalServer, finalPg,finalQueue;
    public StreamService(RunService runs, ServerMetricsPoller poller) { this.runs = runs; this.poller = poller; }
    @Scheduled(fixedRate = 1000) public synchronized void collect() { publish(tick(runs.sample())); }
    private Map<String, Object> tick(RunService.Current current) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", current.id());result.put("runId",current.id()); result.put("status", current.status());
        result.put("running", current.running()); result.put("finalizing", runs.finalizing()); result.put("config", current.config());
        result.put("error", current.error()); result.put("storageWarning", current.storageWarning());
        RunStats.Live live = current.live();
        result.put("elapsedMs", live == null ? 0 : live.elapsedMs());
        result.put("users", live == null ? Map.of() : live.users());
        result.put("outcomes", live == null ? Map.of() : live.outcomes());
        result.put("events", live == null ? Map.of() : live.events());
        result.put("clientRps", live == null ? Map.of() : live.clientRps());
        result.put("pg", live == null ? Map.of() : live.pg());
        result.put("recentEvents", live == null ? List.of() : live.recentEvents());
        result.put("eventHistory", live == null ? List.of() : live.eventHistory());
        result.put("outcomesByChurn",live==null ? Map.of() : live.outcomesByChurn());
        result.put("usersByChurn",live==null ? Map.of() : live.usersByChurn());
        result.put("userGroups",live==null ? Map.of() : live.userGroups());
        result.put("seatsSold",live==null ? null : live.seatsSold());
        result.put("seatsByGrade",live==null ? Map.of() : live.seatsByGrade());
        result.put("refreshing", live == null ? 0 : live.refreshing());
        result.put("avgLatencyMs", current.summary() != null ? current.summary().avgLatencyMs() : Map.of());
        var server = poller.server(); var pg = poller.pg();var queue=poller.queue();var queueStats=poller.queueStats();
        result.put("server", server.value()); result.put("mockPg", pg.value());result.put("queueServer",queue.value());result.put("queueStats",queueStats.value());
        result.put("connections", Map.of("server", server.connection(), "mockPg", pg.connection(),"queue",queue.connection()));
        // Additive tick metadata: a reset from a different run must never enter the result view.
        var timing = runs.timing(current.id());
        if (!Objects.equals(metricsRunId, current.id())) {
            metricsRunId = current.id(); runServer = Map.of(); runPg = Map.of();runQueue=Map.of();runQueueStats=Map.of();
            serverAt = null; pgAt = null;queueAt=null; finalServer = false; finalPg = false;finalQueue=false;
        }
        if (current.id() != null && timing.preparedAt() != null) {
            var at = server.value().get("at");
            java.time.Instant observed = at instanceof String text ? java.time.Instant.parse(text) : null;
            if (!finalServer && belongs(server, current.config().targets().server(), "/admin/metrics", timing.preparedAt())
                    && observed != null && !observed.isBefore(timing.preparedAt())) {
                runServer = server.value(); serverAt = observed;
                finalServer = timing.finishedAt() != null && !observed.isBefore(timing.finishedAt());
            }
            if (!finalPg && belongs(pg, current.config().targets().pg(), "/admin/stats", timing.preparedAt())) {
                runPg = pg.value(); pgAt = pg.connection().lastSuccessAt();
                finalPg = timing.finishedAt() != null && !pgAt.isBefore(timing.finishedAt());
            }
        }
        if(current.id()!=null && timing.preparedAt()!=null && !finalQueue
                && belongs(queue,current.config().targets().queue(),"/admin/metrics",timing.preparedAt())
                && belongs(queueStats,current.config().targets().queue(),"/admin/stats",timing.preparedAt())) {
            runQueue=queue.value();runQueueStats=queueStats.value();queueAt=queue.connection().lastSuccessAt();
            finalQueue=timing.finishedAt()!=null && !queueAt.isBefore(timing.finishedAt());
        }
        var context = new LinkedHashMap<String, Object>();
        context.put("startedAt", timing.startedAt()); context.put("preparedAt", timing.preparedAt());
        context.put("finishedAt", timing.finishedAt()); context.put("serverAt", serverAt); context.put("mockPgAt", pgAt);context.put("queueAt",queueAt);
        context.put("final", (timing.finishedAt() != null && timing.preparedAt() == null) || (finalServer && finalPg && finalQueue));
        result.put("runContext", Collections.unmodifiableMap(context));
        result.put("runMetrics", Map.of("server", runServer, "mockPg", runPg,"queueServer",runQueue,"queueStats",runQueueStats));
        if(server.connection().connected() && pg.connection().connected() && queue.connection().connected() && queueStats.connection().connected() && !runServer.isEmpty() && !runPg.isEmpty() && !runQueue.isEmpty()) runs.observe(runServer,runPg,runQueue);
        result.put("signals",runs.signals());
        result.put("simElapsedSec", runServer.getOrDefault("simElapsedSec", 0));
        result.put("timeScale", current.config() == null ? 4 : current.config().timeScale());
        result.put("saleEndAt", runServer.get("saleEndAt"));
        return Collections.unmodifiableMap(result);
    }
    private static boolean belongs(ServerMetricsPoller.Sample sample, String target, String path, java.time.Instant after) {
        var connection = sample.connection();
        return connection.connected() && (target.replaceAll("/+$", "") + path).equals(connection.url())
                && connection.lastSuccessAt() != null && !connection.lastSuccessAt().isBefore(after);
    }
    public synchronized SseEmitter subscribe() {
        var subscription = new Subscription();
        subscription.emitter.onCompletion(subscription::remove);
        subscription.emitter.onTimeout(() -> { subscription.remove(); subscription.emitter.complete(); });
        subscription.emitter.onError(failure -> subscription.remove());
        subscription.offer(tick(runs.current()));
        subscribers.add(subscription);
        return subscription.emitter;
    }
    private synchronized void publish(Map<String, Object> value) {
        subscribers.forEach(subscription -> subscription.offer(value));
    }
    private final class Subscription {
        final SseEmitter emitter = new SseEmitter(0L);
        final AtomicReference<Map<String, Object>> pending = new AtomicReference<>();
        final AtomicBoolean writing = new AtomicBoolean();
        volatile boolean closed;
        void offer(Map<String, Object> tick) {
            if (closed) return;
            pending.set(tick);
            if (writing.compareAndSet(false, true)) writers.execute(this::write);
        }
        void write() {
            try {
                Map<String, Object> tick;
                while (!closed && (tick = pending.getAndSet(null)) != null)
                    emitter.send(SseEmitter.event().name("tick").data(tick, MediaType.APPLICATION_JSON));
            } catch (IOException failure) { remove(); }
            catch (RuntimeException failure) { remove(); emitter.completeWithError(failure); }
            finally {
                writing.set(false);
                if (!closed && pending.get() != null && writing.compareAndSet(false, true)) writers.execute(this::write);
            }
        }
        void remove() { closed = true; pending.set(null); subscribers.remove(this); }
    }
    @PreDestroy public void close() {
        subscribers.forEach(subscription -> { subscription.remove(); subscription.emitter.complete(); });
        writers.shutdownNow();
    }
}
