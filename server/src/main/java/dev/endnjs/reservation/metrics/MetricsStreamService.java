package dev.endnjs.reservation.metrics;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class MetricsStreamService {
    private final Set<Subscription> subscribers = ConcurrentHashMap.newKeySet();
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    public SseEmitter subscribe(MetricsSnapshot initial) {
        var subscription = new Subscription();
        subscription.emitter.onCompletion(subscription::remove);
        subscription.emitter.onTimeout(() -> { subscription.remove(); subscription.emitter.complete(); });
        subscription.emitter.onError(error -> subscription.remove());
        subscription.offer(initial);
        // Publish the initial event before registration, so a concurrent newer sample cannot be overwritten by it.
        subscribers.add(subscription);
        return subscription.emitter;
    }
    public void publish(MetricsSnapshot snapshot) { subscribers.forEach(subscription -> subscription.offer(snapshot)); }
    private final class Subscription {
        final SseEmitter emitter = new SseEmitter(0L);
        final AtomicReference<MetricsSnapshot> pending = new AtomicReference<>();
        final AtomicBoolean writing = new AtomicBoolean();
        volatile boolean closed;
        void offer(MetricsSnapshot snapshot) {
            if (closed) return;
            pending.set(snapshot);
            if (writing.compareAndSet(false, true)) writers.execute(this::write);
        }
        void write() {
            try {
                MetricsSnapshot snapshot;
                while (!closed && (snapshot = pending.getAndSet(null)) != null) {
                    emitter.send(SseEmitter.event().name("metrics").data(snapshot, MediaType.APPLICATION_JSON));
                }
            } catch (IOException failure) {
                // The servlet container completes an emitter whose send failed with an I/O error.
                remove();
            } catch (RuntimeException failure) {
                remove();
                emitter.completeWithError(failure);
            } finally {
                writing.set(false);
                if (!closed && pending.get() != null && writing.compareAndSet(false, true)) writers.execute(this::write);
            }
        }
        void remove() { closed = true; pending.set(null); subscribers.remove(this); }
    }
    @PreDestroy
    public void close() {
        subscribers.forEach(subscription -> { subscription.remove(); subscription.emitter.complete(); });
        writers.shutdownNow();
    }
}
