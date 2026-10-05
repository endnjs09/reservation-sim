package dev.endnjs.reservation.metrics;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import dev.endnjs.reservation.config.RuntimeConfigStore;
import dev.endnjs.reservation.snapshot.AvailabilitySummary;
import dev.endnjs.reservation.snapshot.StateSnapshotReader;
import dev.endnjs.reservation.snapshot.StateSnapshotReader.MetricsView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** One cached snapshot per second. API readers and SSE subscribers never run measurement queries. */
@Service
public class MetricsService {
    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);
    private final MetricsCollector metrics;
    private final MetricsRepository repository;
    private final StateSnapshotReader snapshots;
    private MetricsView data=new MetricsView(
            new MetricsRepository.Seats("",Map.of(),0,0,0,0,0,Map.of()),AvailabilitySummary.empty());
    private final HikariDataSource datasource;
    private final RuntimeConfigStore configs;
    private final MetricsStreamService streams;
    private final Clock clock;
    private volatile MetricsSnapshot latest;
    private MetricsRepository.Seats seats = new MetricsRepository.Seats("", Map.of(), 0, 0, 0, 0, 0, Map.of());
    private long lockWaits;
    private MetricsSnapshot.Db db=new MetricsSnapshot.Db(0,0);
    private final ResourceMetrics resources;
    private final dev.endnjs.reservation.admission.SlotNotifier notifier;
    private MetricsSnapshot.Pool pool = new MetricsSnapshot.Pool(0, 0, 0, 0);
    private final dev.endnjs.reservation.seat.SeatsGate gate;
    private Map<String,Object> lastGate=Map.of();
    public MetricsService(MetricsCollector metrics, MetricsRepository repository, HikariDataSource datasource,
            RuntimeConfigStore configs, MetricsStreamService streams, Clock clock, StateSnapshotReader snapshots,ResourceMetrics resources,dev.endnjs.reservation.admission.SlotNotifier notifier,dev.endnjs.reservation.seat.SeatsGate gate) {
        this.gate=gate;
        this.metrics = metrics; this.repository = repository; this.datasource = datasource; this.configs = configs;
        this.streams = streams; this.clock = clock;
        this.snapshots=snapshots;this.resources=resources;this.notifier=notifier;
    }
    @Scheduled(fixedRate = 1000, initialDelay = 1000, scheduler = "metricsScheduler")
    public void tick() { runOnce(); }
    public synchronized MetricsSnapshot runOnce() {
        long sampleStart=System.nanoTime();
        var now = clock.instant();
        data=retainOnFailure(() -> snapshots.metrics(now),data);
        seats=data.seats();
        lockWaits = retainOnFailure(repository::lockWaits, lockWaits);
        db=retainOnFailure(() -> repository.locks(now),db);
        var acquisition=resources.acquisition();
        pool = retainOnFailure(() -> {
            var bean = datasource.getHikariPoolMXBean();
            return new MetricsSnapshot.Pool(bean.getActiveConnections(), bean.getIdleConnections(),
                    bean.getThreadsAwaitingConnection(), datasource.getMaximumPoolSize(),acquisition.latency(),acquisition.timeouts());
        }, pool);
        var window=metrics.sample(now);
        var summary=data.summary();
        var phase=summary.phase(now);
        latest = new MetricsSnapshot(now, window.endpoints(), window.inflightTotal(), pool, new MetricsSnapshot.Db(lockWaits,db.lockWaitMaxMs()),
                window.pg(), schedulers(window.schedulers()), seats.seatMap(), seats.heldRemainingMs(),
                window.seatConflicts(), phase, summary.simElapsedSec(now),
                summary.timeScale(), summary.saleEndAt(), seats.depositRemainingMs(),summary.releaseAt(),summary.lastReopenAt(),
                seats.available(),seats.held(),seats.pendingDeposit(),seats.returnPending(),seats.sold(),window.events(),null,summary.soldOut(),window.windowMs(),window.total(),window.cumulative(),resources.http(),resources.jvm(),notifier.metrics(),Math.round((System.nanoTime()-sampleStart)/100_000.0)/10.0,seatsCache(window.windowMs()));
        streams.publish(latest);
        return latest;
    }
    /** 좌석 조회 캐시·새로고침 제한: 누적(reset 이후) + 이번 창의 초당 값. dbReadRps = GET /seats가 실제로 DB를 읽은 초당 수. */
    private Map<String,Object> seatsCache(int windowMs) {
        var now=gate.metrics(configs.current());var result=new LinkedHashMap<String,Object>(now);double seconds=Math.max(.001,windowMs/1000.0);
        for(String key:java.util.List.of("hits","misses","dbReads","rateLimited")) {
            long before=lastGate.get(key) instanceof Number n ? n.longValue() : 0L;long value=((Number)now.get(key)).longValue();
            result.put(key+"Rps",Math.round(Math.max(0,value-before)/seconds*10)/10.0);
        }
        lastGate=now;return Collections.unmodifiableMap(result);
    }
    public MetricsSnapshot snapshot() { return latest; }
    /** Called after admin startup/reset has finished its database transaction. */
    public synchronized void reset() {
        metrics.reset();
        var config = configs.current();
        seats = new MetricsRepository.Seats("A".repeat(config.rows() * config.cols()), Map.of(), (long) config.rows() * config.cols(), 0, 0, 0, 0, Map.of());
        lockWaits = 0;db=new MetricsSnapshot.Db(0,0);resources.reset();lastGate=Map.of();
        pool = new MetricsSnapshot.Pool(0, 0, 0, datasource.getMaximumPoolSize());
        data=new MetricsView(seats,AvailabilitySummary.empty());
        runOnce();
    }
    private <T> T retainOnFailure(Supplier<T> query, T previous) {
        try { return query.get(); }
        catch (RuntimeException failure) { log.debug("Keeping previous metrics after sampling failure", failure); return previous; }
    }
    private static Map<String, Map<String, Object>> schedulers(Map<String, MetricsCollector.SchedulerRun> runs) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        runs.forEach((name, run) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("lastRunAt", run.lastRunAt());
            fields.put(switch (name) { case "expiry", "depositExpiry" -> "expired"; case "reopen" -> "reopened"; default -> "recovered"; }, run.count());
            result.put(name, Collections.unmodifiableMap(fields));
        });
        return Map.copyOf(result);
    }
}
