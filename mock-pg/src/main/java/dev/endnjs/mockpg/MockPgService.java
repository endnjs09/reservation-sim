package dev.endnjs.mockpg;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MockPgService {
    public enum Status { AUTHORIZED, DONE, DECLINED, CANCELED }
    @FunctionalInterface public interface Delay { void sleep(long millis) throws InterruptedException; }
    public record PaymentView(String paymentKey, UUID orderId, int amount, Status status, Instant approvedAt) {}
    public record Approval(Status status, Instant approvedAt) {}
    public record Stats(long authRequests, long authFailed, long confirmRequests, long done,
            long declined, long timedOut, long canceled, int confirmInflight,RequestHistograms.Latency confirmLatency,java.time.Instant serverTime) {
        public Stats(long authRequests,long authFailed,long confirmRequests,long done,long declined,long timedOut,long canceled,int confirmInflight,RequestHistograms.Latency confirmLatency) {
            this(authRequests,authFailed,confirmRequests,done,declined,timedOut,canceled,confirmInflight,confirmLatency,null);
        }
        public Stats(long authRequests,long authFailed,long confirmRequests,long done,long declined,long timedOut,long canceled,int confirmInflight) {
            this(authRequests,authFailed,confirmRequests,done,declined,timedOut,canceled,confirmInflight,new RequestHistograms.Latency(null,null,null,null));
        }
    }
    private static final class Entry {
        final String key;
        final UUID orderId;
        final int amount;
        Status status = Status.AUTHORIZED;
        Instant approvedAt;
        boolean processing;
        Entry(String key, UUID orderId, int amount) { this.key = key; this.orderId = orderId; this.amount = amount; }
        PaymentView view() { return new PaymentView(key, orderId, amount, status, approvedAt); }
    }
    private static final class State {
        final Map<String, Entry> payments = new HashMap<>();
        long authRequests, authFailed, confirmRequests, declined, timedOut, canceled;
        int confirmInflight;
        final RequestHistograms latencies=new RequestHistograms();
    }
    private record Attempt(State state, Entry entry, boolean timeout, boolean declined, long delayMs) {}
    private final Clock clock;
    private final Delay delay;
    private PgConfig config = PgConfig.defaults();
    private SplittableRandom random = new SplittableRandom();
    private State state = new State();

    public MockPgService(Clock clock, Delay delay) { this.clock = clock; this.delay = delay; }
    public synchronized PgConfig config() { return config; }
    public synchronized PgConfig configure(PgConfig next) {
        config = next;
        random = next.seed() == null ? new SplittableRandom() : new SplittableRandom(next.seed());
        return config;
    }
    public synchronized void reset() {
        state = new State();
        random = config.seed() == null ? new SplittableRandom() : new SplittableRandom(config.seed());
    }

    public synchronized String auth(UUID orderId, Integer amount) {
        require(orderId != null && amount != null && amount >= 0);
        state.authRequests++;
        if (random.nextDouble() < config.authFailureRate()) {
            state.authFailed++;
            throw new PgException(400, "AUTH_FAILED");
        }
        String key = "pk_" + UUID.randomUUID();
        state.payments.put(key, new Entry(key, orderId, amount));
        return key;
    }

    public Approval confirm(String key, UUID orderId, Integer amount) {
        Attempt attempt = begin(key, orderId, amount);
        long waitStart=System.nanoTime();
        var measured=attempt.state().latencies.started("confirm");
        try {
            // Never hold the state monitor while sleeping: lookup and cancel remain available.
            try { delay.sleep(attempt.delayMs()); } finally { measured.finishElapsed(System.nanoTime()-waitStart,0,null); }
            synchronized (this) {
                Entry entry = attempt.entry();
                if (!attempt.timeout() && entry.status == Status.AUTHORIZED) {
                    if (attempt.declined()) {
                        entry.status = Status.DECLINED;
                        attempt.state().declined++;
                    } else {
                        entry.status = Status.DONE;
                        entry.approvedAt = clock.instant();
                    }
                }
                if (entry.status == Status.DECLINED) throw new PgException(400, "INSUFFICIENT_FUNDS");
                if (entry.status != Status.DONE) throw new PgException(400, "INVALID_REQUEST");
                return new Approval(Status.DONE, entry.approvedAt);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PgException(503, "INTERRUPTED");
        } finally {
            synchronized (this) {
                attempt.entry().processing = false;
                attempt.state().confirmInflight--;
            }
        }
    }

    private synchronized Attempt begin(String key, UUID orderId, Integer amount) {
        state.confirmRequests++;
        require(key != null && !key.isBlank() && orderId != null && amount != null && amount >= 0);
        Entry entry = state.payments.get(key);
        require(entry != null && entry.orderId.equals(orderId) && entry.amount == amount
                && entry.status == Status.AUTHORIZED && !entry.processing);
        entry.processing = true;
        state.confirmInflight++;
        boolean timeout = random.nextDouble() < config.timeoutRate();
        boolean declined = !timeout && random.nextDouble() < config.declineRate();
        long delayMs;
        if (timeout) {
            state.timedOut++;
            // Persist the ambiguous outcome before delaying the response. A later cancel stays final.
            if (random.nextBoolean()) {
                entry.status = Status.DONE;
                entry.approvedAt = clock.instant();
            }
            delayMs = (long) config.confirmMaxMs() + 10000;
        } else {
            delayMs = random.nextLong(config.confirmMinMs(), (long) config.confirmMaxMs() + 1);
        }
        return new Attempt(state, entry, timeout, declined, delayMs);
    }

    public synchronized Status status(String key) {
        Entry entry = state.payments.get(key);
        if (entry == null) throw new PgException(404, "NOT_FOUND");
        return entry.status;
    }
    public synchronized Status cancel(String key) {
        Entry entry = state.payments.get(key);
        if (entry == null) throw new PgException(404, "NOT_FOUND");
        if (entry.status == Status.DONE || entry.status == Status.AUTHORIZED) {
            entry.status = Status.CANCELED;
            state.canceled++;
        }
        return Status.CANCELED;
    }
    public synchronized List<PaymentView> payments() {
        return state.payments.values().stream().map(Entry::view)
                .sorted(Comparator.comparing(PaymentView::paymentKey)).toList();
    }
    public synchronized Stats stats() {
        long done = state.payments.values().stream().filter(p -> p.status == Status.DONE).count();
        return new Stats(state.authRequests, state.authFailed, state.confirmRequests, done,
                state.declined, state.timedOut, state.canceled, state.confirmInflight,cumulativeLatency(),clock.instant());
    }
    private RequestHistograms.Latency cumulativeLatency() {
        var cumulative=(Map<?,?>)state.latencies.sample(1).cumulative().get("total");
        return new RequestHistograms.Latency((Double)cumulative.get("p50"),(Double)cumulative.get("p95"),(Double)cumulative.get("p99"),(Double)cumulative.get("max"));
    }
    private static void require(boolean valid) { if (!valid) throw new PgException(400, "INVALID_REQUEST"); }
}
