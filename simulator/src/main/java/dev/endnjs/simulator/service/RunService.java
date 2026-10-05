package dev.endnjs.simulator.service;

import dev.endnjs.simulator.engine.*;
import dev.endnjs.simulator.http.*;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static dev.endnjs.simulator.engine.HttpTransport.Target.*;
import static dev.endnjs.simulator.service.RunStore.object;

@Service
public class RunService {
    private static final Logger log=LoggerFactory.getLogger(RunService.class);
    private final JsonCodec json=new JsonCodec();
    private final RunStore store;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private volatile RunEngine engine;
    private RunConfig config=RunConfig.defaults();
    private String id,status="IDLE",error,storageWarning;
    private boolean active,closed,finalizing;
    private volatile boolean manualStop;
    private Future<?> execution;
    private Summary summary;
    private RunStats.Live live;
    private RunMeasurements measurements;
    private Map<String,Object> signals=Map.of(),lastServer=Map.of();
    private final Map<String,Instant> failures=new HashMap<>();
    private long lastWrittenSecond=-1;
    private SwapWatch swap=new SwapWatch(HostMemory::read);
    public record PastRun(String id,Summary summary) {}
    public record Timing(Instant startedAt,Instant preparedAt,Instant finishedAt) {}
    public record Current(String id,String status,boolean running,RunConfig config,RunStats.Live live,Summary summary,String error,String storageWarning) {}
    @org.springframework.beans.factory.annotation.Autowired
    public RunService(@Value("${simulator.runs-dir:./simulator/runs}") String directory,
            @Value("${runs.keep:${simulator.runs-keep:50}}") int keep) throws IOException {
        store=new RunStore(Path.of(directory),Path.of("./runs"),keep);
    }
    public RunService(String directory) throws IOException { this(directory,50); }
    public RunStore store() { return store; }
    public synchronized Current start(RunConfig supplied) {
        if(closed) throw new IllegalStateException("Service is shutting down");
        if(active) throw new ApiFailure(409,"RUN_ACTIVE","이미 실행 중입니다.");
        if(!supplied.queueMode().equals("EXTERNAL")) throw new ApiFailure(400,"QUEUE_MODE_UNAVAILABLE","새 실행은 EXTERNAL 대기열을 사용합니다.");
        var transport=new JdkHttpTransport(supplied.targets(),json);
        try { preflight(supplied,transport); }
        catch(RuntimeException invalid) { transport.close();throw invalid; }
        engine=new RunEngine(supplied,transport);config=supplied;status="RUNNING";
        active=true;finalizing=false;manualStop=false;summary=null;error=null;signals=Map.of();lastServer=Map.of();failures.clear();lastWrittenSecond=-1;swap=new SwapWatch(HostMemory::read);
        measurements=new RunMeasurements(config);live=engine.stats().sampleLive(true);
        try { id=store.create(metadata(config,engine.stats().startedAt())); }
        catch(IOException failure) { active=false;transport.close();throw new ApiFailure(500,"STORAGE_ERROR","실행 기록을 만들지 못했습니다."); }
        String runId=id;RunEngine runningEngine=engine;
        runningEngine.onPrepared(() -> prepared(runId,transport,supplied,runningEngine));
        execution=executor.submit(() -> execute(runId,runningEngine,transport));return current();
    }
    static void preflight(RunConfig supplied,HttpTransport transport) {
        try { RunPreflight.check(supplied,transport); }
        catch(RunPreflight.TargetUnavailable failure) { throw new TargetDown(switch(failure.target()) { case SERVER -> "server"; case PG -> "mockPg"; case QUEUE -> "queue"; }); }
    }
    public static final class TargetDown extends ApiFailure {
        public final String target;
        TargetDown(String target) { super(409,"TARGET_DOWN",target+"에 연결할 수 없습니다.");this.target=target; }
    }
    private void prepared(String runId,HttpTransport transport,RunConfig config,RunEngine runningEngine) throws IOException,InterruptedException {
        recordClock(runId,transport,config,runningEngine.anchor());
        var stats=get(transport,SERVER,"/admin/stats");var metrics=get(transport,SERVER,"/admin/metrics");
        var actual=object(stats.get("config"));var http=object(metrics.get("http"));var facts=new LinkedHashMap<String,Object>();
        facts.put("strategy",actual.get("strategy"));facts.put("dbBackstop",actual.get("dbBackstop"));facts.put("poolMax",object(metrics.get("pool")).get("max"));
        facts.put("threadsMax",http.get("threadsMax"));facts.put("virtualThreads",http.get("virtualThreads"));
        // 좌석 조회 새로고침 제한·캐시: 서버가 실제로 쓰는 값 (reset 뒤 /admin/stats config)
        for(String key:List.of("seatsRateLimitEnabled","seatsMinIntervalSec","seatsCacheSec")) facts.put(key,actual.get(key));
        store.server(runId,facts);
        var bench=BenchProfile.verify(BenchProfile.read(),object(metrics.get("jvm")).get("heapMaxMb") instanceof Number n ? n.doubleValue() : null,Runtime.getRuntime().maxMemory());
        if(bench!=null) store.environment(runId,"bench",bench);
        synchronized(this) { lastServer=metrics; }
    }
    private Map<String,Object> metadata(RunConfig config,Instant startedAt) {
        var result=new LinkedHashMap<String,Object>();result.put("schemaVersion",5);result.put("label",config.label());result.put("notes",config.notes());result.put("pinned",false);
        result.put("status","RUNNING");result.put("statusReason",null);result.put("startedAt",startedAt.toString());result.put("endedAt",null);result.put("durationMs",0);result.put("simDurationSec",0);result.put("timeScale",config.timeScale());
        result.put("config",json.decode(json.encode(config)));result.put("server",Map.of("strategy",config.strategy(),"dbBackstop",config.dbBackstop()));result.put("queueMode",config.queueMode());result.put("thresholds",json.decode(json.encode(config.thresholds())));
        var environment=new LinkedHashMap<String,Object>();environment.put("cpuCores",Runtime.getRuntime().availableProcessors());environment.put("javaVersion",System.getProperty("java.version"));environment.put("os",System.getProperty("os.name"));environment.put("memAvailableMbAtStart",HostMemory.read().availableMb());environment.put("gitCommit",gitCommit());environment.put("bench",BenchProfile.read());result.put("environment",environment);
        result.put("fingerprint",RunComparison.fingerprint(object(result.get("config"))));return result;
    }
    private String gitCommit() {
        try { var process=new ProcessBuilder("git","rev-parse","--short","HEAD").redirectError(ProcessBuilder.Redirect.DISCARD).start();if(!process.waitFor(2,TimeUnit.SECONDS)) { process.destroyForcibly();return null; }return process.exitValue()==0 ? new String(process.getInputStream().readAllBytes()).trim() : null; }
        catch(IOException|InterruptedException failure) { if(failure instanceof InterruptedException) Thread.currentThread().interrupt();return null; }
    }
    private void execute(String runId,RunEngine runningEngine,JdkHttpTransport transport) {
        Summary result;String reason=null;
        try {
            result=runningEngine.run();reason=runningEngine.statusReason();
            if(reason==null && result.outcomes().getOrDefault("error",0L)>0) reason="USER_ERRORS";
            finalSample(transport,runningEngine); // 1초 표본보다 먼저 끝나도 마지막 서버 상태(판매 종료)를 남긴다
            synchronized(this) { summary=result;live=completedLive(runningEngine.stats().sampleLive(false));finalizing=true; }
            // User work has stopped. Keep the run exclusively owned while the forensic dump quiesces.
            // 남은 HELD·CONFIRMING·PENDING_DEPOSIT이 없어지면 바로 다음 단계, 최대 holdTtl+confirmDeadline+5초 (docs/DECISION_CLAUDE.md)
            boolean quiesced=reason==null && !manualStop;
            if(quiesced) {
                long end=System.nanoTime()+config.realDuration(config.holdTtlSec()+config.confirmDeadlineSec()+5).toNanos();
                while(System.nanoTime()<end && !manualStop && !closed && !settled(transport)) Thread.sleep(Math.min(1000,Math.max(1,(end-System.nanoTime())/1_000_000)));
                quiesced=!manualStop && !closed;
            }
            Map<String,Object> finalMetrics;
            try { finalMetrics=get(transport,SERVER,"/admin/metrics"); }
            catch(IOException|InterruptedException failure) { finalMetrics=lastServer;if(reason==null) reason="METRICS_LOST"; }
            result=measurements.finish(result,finalMetrics,runningEngine.stats().clientMetrics());
            var snapshot=new LinkedHashMap<String,Object>();snapshot.put("takenAt",anchoredNow(runningEngine).toString());snapshot.put("quiesced",quiesced);
            var snapshotErrors=new ArrayList<String>();
            for(var target:List.of(SERVER,PG,QUEUE)) {
                try { snapshot.put(target==SERVER ? "server" : target==QUEUE ? "queue" : "mockPg",target!=PG ? get(transport,target,"/admin/snapshot") : get(transport,target,"/admin/payments").get("$items")); }
                catch(IOException|InterruptedException failure) { snapshot.put(target==SERVER ? "server" : target==QUEUE ? "queue" : "mockPg",null);snapshotErrors.add(target.name()); }
            }
            if(!snapshotErrors.isEmpty()) { snapshot.put("quiesced",false);snapshot.put("errors",snapshotErrors);if(reason==null) reason="SNAPSHOT_FAILED"; }
            String finalStatus=manualStop ? "STOPPED" : reason!=null || result.outcomes().getOrDefault("error",0L)>0 ? "FAILED" : "COMPLETED";
            store.finish(runId,result,snapshot,finalStatus,manualStop ? "USER_STOP" : reason);
            synchronized(this) { summary=result;status=finalStatus;error=reason;live=completedLive(runningEngine.stats().sampleLive(false));active=false;finalizing=false; }
        } catch(IOException|InterruptedException|RuntimeException failure) {
            log.error("Run {} finalization failed",runId,failure);
            result=runningEngine.stats().summary();
            synchronized(this) { summary=result;status="FAILED";error="STORAGE_ERROR";storageWarning="실행 기록 저장 실패";active=false;finalizing=false; }
            try { store.finish(runId,result,Map.of("takenAt",anchoredNow(runningEngine).toString(),"quiesced",false,"error","FINALIZATION_FAILED"),"FAILED","FINALIZATION_FAILED"); }
            catch(IOException secondary) { log.error("Cannot save failure record {}",runId,secondary); }
        } finally { transport.close(); }
    }
    /** 3장: reset 직후 각 서버 시각과 자기 anchor 시각의 차이(왕복 절반 보정)를 run.json clockOffsetsMs에 남긴다. */
    private void recordClock(String runId,HttpTransport transport,RunConfig config,AnchorTime.Anchor anchor) throws IOException,InterruptedException {
        var offsets=new LinkedHashMap<String,Object>();
        for(var target:List.of(Map.entry("server",SERVER),Map.entry("queue",QUEUE),Map.entry("mockPg",PG))) offsets.put(target.getKey(),clockOffsetMs(transport,target.getValue(),anchor));
        store.clock(runId,anchor.at(),offsets);
        long t=(System.nanoTime()-anchor.nanos())*config.timeScale()/1_000_000_000L;
        for(var entry:offsets.entrySet()) if(entry.getValue() instanceof Long offset && Math.abs(offset)>CLOCK_OFFSET_HIGH_MS) {
            var event=new LinkedHashMap<String,Object>();event.put("t",t);event.put("type","CLOCK_OFFSET_HIGH");event.put("target",entry.getKey());event.put("offsetMs",offset);
            store.append(runId,"events.ndjson",event);
        }
    }
    /** 3장: 실행 기록의 시각도 같은 기준점에서 계산한다. 기준점 전이면 시스템 시계. */
    /** 지금 실행(또는 마지막 실행)의 anchor 시계. 실행 전이면 시스템 시계. 계측 poller와 실행 기록이 같은 시간축을 쓰게 한다. */
    public Instant clockNow() { return anchoredNow(engine); }
    static Instant anchoredNow(RunEngine engine) {
        var anchor=engine==null ? null : engine.anchor();
        return anchor==null ? Instant.now() : anchor.at().plusNanos(System.nanoTime()-anchor.nanos());
    }
    static final long CLOCK_OFFSET_HIGH_MS=500;
    static Long clockOffsetMs(HttpTransport transport,HttpTransport.Target target,AnchorTime.Anchor anchor) throws InterruptedException {
        try {
            long sent=System.nanoTime();var body=get(transport,target,"/admin/stats");long received=System.nanoTime();
            if(!(body.get("serverTime") instanceof String text)) return null;
            Instant local=anchor.at().plusNanos((sent+received)/2-anchor.nanos());
            return Duration.between(local,Instant.parse(text)).toMillis();
        } catch(IOException|RuntimeException unavailable) { return null; }
    }
    private static Map<String,Object> get(HttpTransport transport,HttpTransport.Target target,String path) throws IOException,InterruptedException {
        var response=transport.exchange(new HttpTransport.Request(target,"GET",path,Map.of(),null,Duration.ofSeconds(3)));
        if(!response.ok()) throw new IOException("HTTP "+response.status()+" for "+path);return response.body();
    }
    /** 예약 서버에 남은 HELD·CONFIRMING·PENDING_DEPOSIT이 없으면 true. 읽지 못하면 false (계속 기다림). */
    private static boolean settled(HttpTransport transport) throws InterruptedException {
        try {
            var reservations=object(get(transport,SERVER,"/admin/stats").get("reservations"));
            return List.of("HELD","CONFIRMING","PENDING_DEPOSIT").stream().allMatch(k -> RunMeasurements.number(reservations.get(k))==0);
        } catch(IOException unavailable) { return false; }
    }
    /**
     * 엔진이 끝난 순간의 서버 상태로 표본 하나를 더 남긴다: 1초 표본보다 사용자가 먼저 끝나도 phase=ENDED → SALE_ENDED가 기록된다.
     * 시계열 줄은 직전 표본과 다른 초일 때만 (같은 t를 두 번 쓰지 않음), 사건은 늘.
     */
    private void finalSample(HttpTransport transport,RunEngine runningEngine) throws InterruptedException {
        Map<String,Object> server,pg,queue;
        try { server=get(transport,SERVER,"/admin/metrics");pg=get(transport,PG,"/admin/stats");queue=get(transport,QUEUE,"/admin/metrics"); }
        catch(IOException unavailable) { return; }
        synchronized(this) {
            if(runningEngine.preparedAt()==null || measurements==null) return;
            var last=runningEngine.stats().sampleLive(false);long second=last.elapsedMs()/1000;
            var frame=measurements.sample(last.elapsedMs(),server,pg,queue,last,runningEngine.stats().clientMetrics());signals=frame.signals();lastServer=server;
            try {
                if(second!=lastWrittenSecond) store.append(id,"timeseries.ndjson",frame.series());
                for(var event:frame.events()) store.append(id,"events.ndjson",event);
                lastWrittenSecond=second;
            } catch(IOException failure) { storageWarning="시계열 저장 실패"; }
        }
    }
    public Current stop() {
        Future<?> pending;
        synchronized(this) { if(!active) return current();manualStop=true;status="STOPPING";engine.stop();pending=execution; }
        try { pending.get(20,TimeUnit.SECONDS); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch(ExecutionException|TimeoutException failure) { log.warn("Stop is still finalizing",failure); }
        return current();
    }
    public synchronized Current current() { return new Current(id,status,active,config,live,summary,error,storageWarning); }
    public synchronized Timing timing(String runId) {
        if(!Objects.equals(id,runId)) return new Timing(null,null,null);
        return new Timing(engine==null ? null : engine.stats().startedAt(),engine==null ? null : engine.preparedAt(),summary==null ? null : summary.startedAt().plusMillis(summary.durationMs()));
    }
    public synchronized Current sample() { if(active && summary==null) live=engine.stats().sampleLive(true);return current(); }
    public synchronized Map<String,Object> signals() { return signals; }
    /** 엔진은 끝났고 남은 선점·결제 정리를 기다리는 중 (status는 RUNNING 유지, 화면은 "정리 중"). */
    public synchronized boolean finalizing() { return finalizing; }
    public synchronized void connection(String target,ServerMetricsPoller.Connection connection) {
        if(!active || summary!=null || engine.preparedAt()==null) return;
        if(connection.connected()) failures.remove(target);
        else {
            Instant now=connection.checkedAt()==null ? clockNow() : connection.checkedAt();
            Instant since=failures.computeIfAbsent(target,k -> now);
            if(Duration.between(since,now).toMillis()>=5000) { error="METRICS_LOST";engine.fail("METRICS_LOST"); }
        }
    }
    public synchronized void observe(Map<String,Object> server,Map<String,Object> pg) { observe(server,pg,Map.of()); }
    public synchronized void observe(Map<String,Object> server,Map<String,Object> pg,Map<String,Object> queue) {
        if(!active || summary!=null || live==null || engine.preparedAt()==null) return;
        long second=live.elapsedMs()/1000;if(second==lastWrittenSecond) return;
        var frame=measurements.sample(live.elapsedMs(),server,pg,queue,live,engine.stats().clientMetrics());signals=frame.signals();lastServer=server;lastWrittenSecond=second;
        var swapEvent=swap.check(live.elapsedMs()*config.timeScale()/1000);
        try { store.append(id,"timeseries.ndjson",frame.series());for(var event:frame.events()) store.append(id,"events.ndjson",event);if(swapEvent!=null) store.append(id,"events.ndjson",swapEvent); }
        catch(IOException failure) { storageWarning="시계열 저장 실패";engine.fail("STORAGE_ERROR"); }
    }
    private RunStats.Live completedLive(RunStats.Live sample) {
        return new RunStats.Live(summary.durationMs(),false,sample.users(),summary.outcomes(),summary.events(),sample.clientRps(),sample.pg(),sample.recentEvents(),sample.refreshing(),summary.outcomesByChurn(),sample.usersByChurn(),summary.seatsSold(),summary.seatsByGrade(),sample.userGroups(),sample.eventHistory());
    }
    public List<PastRun> history() { try { return ((List<?>)store.list().get("runs")).stream().map(RunStore::object).map(r -> { var runId=(String)r.get("runId");try { return new PastRun(runId,store.summary(runId)); } catch(IOException failure) { return null; } }).filter(Objects::nonNull).toList(); }catch(IOException failure) { throw new ApiFailure(500,"STORAGE_ERROR","실행 기록 읽기 실패"); } }
    public Summary summary(String runId) { try { return store.summary(runId); }catch(IOException failure) { throw new ApiFailure(404,"RUN_NOT_FOUND","실행 기록이 없습니다."); } }
    @PreDestroy public void close() {
        synchronized(this) { closed=true;manualStop=true;if(active) engine.stop(); }
        executor.shutdown();try { if(!executor.awaitTermination(20,TimeUnit.SECONDS)) executor.shutdownNow(); }catch(InterruptedException interrupted) { executor.shutdownNow();Thread.currentThread().interrupt(); }
    }
}
