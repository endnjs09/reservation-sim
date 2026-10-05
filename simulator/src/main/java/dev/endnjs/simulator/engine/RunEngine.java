package dev.endnjs.simulator.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.endnjs.simulator.engine.HttpTransport.Target.*;

/** A single use run. No Spring or JSON implementation is required by the engine. */
public final class RunEngine {
    private final RunConfig config;
    private final AnchorTime time;
    private volatile java.time.Instant preparedAt;
    private final String runEpoch=java.util.UUID.randomUUID().toString();
    private volatile java.time.Instant saleEndAt;
    private volatile String statusReason;
    public String statusReason() { return statusReason; }
    public String runEpoch() { return runEpoch; }
    public void fail(String reason) { statusReason=reason;stop(); }
    private final RunControl control;
    private final RunStats stats;
    private final RunHttp http;
    private final RevisitSchedule revisits;
    private final java.util.List<VirtualUser.Profile> profiles;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Thread runner;
    private volatile ExecutorService workers;
    private volatile boolean running;
    @FunctionalInterface public interface PreparedCallback { void run() throws IOException,InterruptedException; }
    private PreparedCallback preparedCallback=() -> {};
    public void onPrepared(PreparedCallback callback) { preparedCallback=callback; }
    public RunEngine(RunConfig config, HttpTransport transport) { this(config, transport, RunTime.system()); }
    public RunEngine(RunConfig config, HttpTransport transport, RunTime time) {
        this.config = config; this.time = new AnchorTime(time); control = new RunControl(this.time);control.requestTimeoutMs(config.requestTimeoutMs());
        var plans = new ArrayList<VirtualUser.Profile>();
        for (int i = 0; i < config.users(); i++) plans.add(VirtualUser.profile(config, i));
        profiles = java.util.List.copyOf(plans);
        stats = new RunStats(config,this.time,profiles.stream().map(VirtualUser.Profile::persona).toList(),profiles.stream().map(VirtualUser.Profile::churn).toList());
        http = new RunHttp(transport, control, stats);
        revisits = new RevisitSchedule(http,this.time,config,stats);
    }
    public RunStats stats() { return stats; }
    /** 3장 기준점. run() 전에는 null. */
    public AnchorTime.Anchor anchor() { return time.current(); }
    public java.time.Instant saleEndAt() { return saleEndAt; }
    public java.time.Instant preparedAt() { return preparedAt; }
    public boolean running() { return running; }
    public synchronized void stop() {
        control.stop();
        var executor = workers; if (executor != null) executor.shutdownNow();
        Thread thread = runner; if (thread != null) thread.interrupt();
    }
    public Summary run() {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Engine already used");
        runner = Thread.currentThread(); running = true;
        var anchor = time.anchor(); // 3장: saleEndAt·startedAt·t의 유일한 기준점
        stats.start(anchor.at(), anchor.nanos()); control.start(config.timeLimitSec(),config.timeScale());
        var timer = Executors.newScheduledThreadPool(2,Thread.ofPlatform().daemon().name("run-clock-",0).factory());
        timer.schedule(() -> fail("TIME_LIMIT"),config.realDuration(config.timeLimitSec()).toNanos(),TimeUnit.NANOSECONDS);
        try {
            prepare();
            preparedCallback.run();
            preparedAt = time.instant();
            revisits.start(preparedAt,saleEndAt);
            timer.scheduleAtFixedRate(() -> {
                try { revisits.signal(); }
                catch(InterruptedException stopped) { Thread.currentThread().interrupt(); }
            },1,1,TimeUnit.SECONDS);
            workers = Executors.newVirtualThreadPerTaskExecutor();
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < config.users(); i++) {
                control.check();
                futures.add(workers.submit(new VirtualUser(i, profiles.get(i), config, http, control, stats, time, revisits)));
            }
            for (Future<?> future : futures) future.get();
            boolean sampled=revisits.refresh();
            while(sampled && revisits.backgroundWork()) {
                control.pause(1000);
                sampled=revisits.refresh();
            }
            if(!sampled) stats.finalInventoryUnavailable();
        } catch (InterruptedException stopped) {
            control.stop();
        } catch (IOException | RuntimeException | java.util.concurrent.ExecutionException failure) {
            if(!control.stopped()) statusReason=preparedAt==null ? "RESET_FAILED" : "RUN_ERROR";
            stats.note("run", "실행 오류: " + failure.getMessage());
            stats.finishPending(control.stopped() ? RunStats.Outcome.incomplete : RunStats.Outcome.error);
        } finally {
            timer.shutdownNow();
            var executor = workers;
            if (executor != null) {
                if (control.stopped()) executor.shutdownNow(); else executor.shutdown();
                boolean done = false;
                while (!done) {
                    try { done = executor.awaitTermination(1, TimeUnit.SECONDS); }
                    catch (InterruptedException stopped) { control.stop(); executor.shutdownNow(); }
                }
            }
            stats.finishPending(RunStats.Outcome.incomplete);
            synchronized (this) {
                // Stop must finish delivering its interrupt before the caller starts saving the summary.
                runner = null; running = false;
                Thread.interrupted();
            }
        }
        return stats.summary();
    }
    private void prepare() throws IOException, InterruptedException {
        var anchorAt=time.anchor().at();
        saleEndAt=anchorAt.plus(config.realDuration(config.saleDurationSec()));
        if(!config.queueMode().equals("EXTERNAL")) throw new IllegalArgumentException("New runs require EXTERNAL queue mode");
        // 서버별 anchorAt = 기준 시각 + 보내기 직전까지의 단조 경과 (anchor 시계의 "지금"). 서버는 받은 순간을 이 값으로 삼으므로
        // 앞 reset들의 처리 시간이 뒤 서버의 시각 차이로 쌓이지 않는다 (docs/DECISION_CLAUDE.md, 전후 실측 기록). saleEndAt은 기준 시각에서 고정.
        check(http.send("queue.reset",QUEUE,"POST","/admin/reset",Map.of(),Map.of(
                "maxActive",config.maxActive(),"admitPerSec",config.admitPerSec(),"admissionTtlSec",config.admissionTtlSec(),
                "busyMaxExtraSec",config.busyMaxExtraSec(),"timeScale",config.timeScale(),"saleEndAt",saleEndAt,
                "closeQueueOnSoldOut",config.closeQueueOnSoldOut(),"runEpoch",runEpoch,"anchorAt",time.instant())));
        // 오래 걸리는 예약 서버 reset(TRUNCATE·좌석 생성)은 마지막에 보낸다 (그 뒤 pg.config만 남음).
        check(http.send("pg.reset", PG, "POST", "/admin/reset", Map.of(), Map.of("anchorAt",time.instant())));
        check(http.send("server.reset", SERVER, "POST", "/admin/reset", Map.of(), Map.ofEntries(
                Map.entry("rows", config.rows()), Map.entry("cols", config.cols()), Map.entry("grades", config.grades()),
                Map.entry("holdTtlSec", config.holdTtlSec()), Map.entry("confirmDeadlineSec", config.confirmDeadlineSec()),
                Map.entry("strategy", config.strategy()), Map.entry("dbBackstop", config.dbBackstop()),
                Map.entry("closeQueueOnSoldOut", config.closeQueueOnSoldOut()),
                Map.entry("maxSeatsPerUser",config.maxSeatsPerUser()),Map.entry("depositDeadlineSec",config.depositDeadlineSec()),
                Map.entry("returnDelaySec",config.returnDelaySec()),Map.entry("reopenWindowSec",config.reopenWindowSec()),
                Map.entry("runEpoch",runEpoch),Map.entry("anchorAt",time.instant()),Map.entry("saleEndAt",saleEndAt),Map.entry("timeScale", config.timeScale()), Map.entry("saleDurationSec", config.saleDurationSec()),
                Map.entry("seatsRateLimitEnabled",config.seatsRateLimitEnabled()),Map.entry("seatsMinIntervalSec",config.seatsMinIntervalSec()),Map.entry("seatsCacheSec",config.seatsCacheSec()))));
        check(http.send("pg.config", PG, "PUT", "/admin/config", Map.of(), Map.of("authFailureRate", config.authFailureRate(),
                "declineRate", config.declineRate(), "timeoutRate", config.timeoutRate(), "confirmMinMs", config.confirmMinMs(),
                "confirmMaxMs", config.confirmMaxMs(), "seed", config.seed())));
    }
    private static void check(HttpTransport.Response response) {
        if (!response.ok()) throw new IllegalStateException("Run setup HTTP " + response.status());
    }
}
