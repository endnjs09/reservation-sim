package dev.endnjs.simulator.service;

import dev.endnjs.simulator.engine.*;
import java.util.*;
import static dev.endnjs.simulator.service.RunStore.object;

/** The same signals object is sent to SSE and appended once to the run series. */
public final class RunMeasurements {
    private final RunConfig config;
    private final Map<String,Object> peaks=new LinkedHashMap<>();
    private long samples,rushSamples,rushErrSamples,poolSat,sloSec;
    private double rushP95,rushErr;
    private String phase;
    private boolean slo,pool;
    private long dropped,reopens,busyCaps;
    private Long soldOutAt;
    public RunMeasurements(RunConfig config) { this.config=config; }
    public record Frame(Map<String,Object> signals,Map<String,Object> series,List<Map<String,Object>> events) {}
    public Frame sample(long elapsedMs,Map<String,Object> server,Map<String,Object> pg,RunStats.Live live,RunStats.ClientMetrics client) {
        return sample(elapsedMs,server,pg,Map.of(),live,client);
    }
    public Frame sample(long elapsedMs,Map<String,Object> server,Map<String,Object> pg,Map<String,Object> externalQueue,RunStats.Live live,RunStats.ClientMetrics client) {
        long t=elapsedMs*config.timeScale()/1000;var total=object(server.get("total"));var latency=object(total.get("latency"));
        var endpoints=object(server.get("endpoints"));var p=object(server.get("pool"));var db=object(server.get("db"));var http=object(server.get("http"));
        double responses=0,errors=0;
        for(var endpointEntry:endpoints.entrySet()) {
            // 8.2 키는 depositPay·cancel. 예전 서버는 같은 값을 deposit.pay·reservation.cancel로도 보냈으므로 둘 다 있으면 한 번만 센다
            String name=endpointEntry.getKey();
            if(name.equals("deposit.pay") && endpoints.containsKey("depositPay") || name.equals("reservation.cancel") && endpoints.containsKey("cancel")) continue;
            var endpoint=endpointEntry.getValue();
            // 429(좌석 조회 새로고침 제한)는 에러율의 분자·분모 모두에서 뺀다 (docs/DECISION_CLAUDE.md). 예약 서버가 429를 내는 경우는 이것뿐
            var counts=object(object(endpoint).get("status"));for(var entry:counts.entrySet()) { if(entry.getKey().equals("429")) continue;double n=number(entry.getValue());responses+=n;if(!entry.getKey().equals("2xx")) errors+=n; }
        }
        var signals=new LinkedHashMap<String,Object>();signals.put("rps",total.get("rps"));signals.put("p50",latency.get("p50"));signals.put("p95",latency.get("p95"));signals.put("p99",latency.get("p99"));
        signals.put("errPct",responses==0 ? 0 : round(errors/responses*100));
        var seatsCache=object(server.get("seatsCache"));signals.put("rateLimitedRps",seatsCache.get("rateLimitedRps"));
        signals.put("failPct",client.serverSent()==0 ? 0 : round(client.serverFailed()*100.0/client.serverSent()));
        signals.put("poolPct",number(p.get("max"))==0 ? null : round(number(p.get("active"))/number(p.get("max"))*100));
        signals.put("poolPending",p.get("pending"));signals.put("lockWaits",db.get("lockWaits"));
        signals.put("threadsBusyPct",number(http.get("threadsMax"))==0 ? null : round(number(http.get("threadsBusy"))/number(http.get("threadsMax"))*100));
        var thresholds=config.thresholds();signals.put("levels",Map.of("p95",level(signals.get("p95"),thresholds.p95WarnMs(),thresholds.p95SloMs()),"err",level(signals.get("errPct"),thresholds.errWarnPct(),thresholds.errBadPct()),"pool",level(signals.get("poolPct"),thresholds.poolWarnPct(),thresholds.poolBadPct())));
        var events=new ArrayList<Map<String,Object>>();String next=Objects.toString(server.get("phase"),"OPEN");
        if(!next.equals(phase)) {
            events.add(event(t,"PHASE",Map.of("to",next)));if(next.equals("ENDED")) events.add(event(t,"SALE_ENDED",Map.of()));
            phase=next;
        }
        if(soldOutAt==null && Set.of("SOLD_OUT","RESALE").contains(next)) { soldOutAt=t;events.add(event(t,"SOLD_OUT",Map.of())); }
        long reopenCount=live.events().getOrDefault("reopenSeen",0L);
        if(reopenCount>reopens) {
            var details=new LinkedHashMap<String,Object>();double seats=number(object(server.get("events")).get("REOPEN"));
            details.put("seats",seats>0 ? seats : null);details.put("revisits",live.events().getOrDefault("revisits",0L));
            events.add(event(t,"REOPEN",details));reopens=reopenCount;
        }
        boolean nowSlo=signals.get("p95")!=null && number(signals.get("p95"))>=thresholds.p95SloMs();
        boolean nowPool=signals.get("poolPct")!=null && number(signals.get("poolPct"))>=thresholds.poolBadPct();
        if(nowSlo!=slo) { events.add(event(t,nowSlo ? "SLO_BREACH_START" : "SLO_BREACH_END",Map.of()));slo=nowSlo; }
        if(nowPool!=pool) { events.add(event(t,nowPool ? "POOL_SAT_START" : "POOL_SAT_END",Map.of()));pool=nowPool; }
        long drops=(long)number(object(server.get("notifier")).get("dropped"));
        if(drops>dropped) { events.add(event(t,"NOTIFY_DROPPED",Map.of("count",drops-dropped)));dropped=drops; }
        long caps=(long)number(externalQueue.get("expiredByBusyCap"));
        if(caps>busyCaps) { events.add(event(t,"BUSY_CAP_EXPIRED",Map.of("count",caps-busyCaps)));busyCaps=caps; }
        samples++;if(nowPool) poolSat++;if(nowSlo) sloSec++;
        if(next.equals("RUSH")) { if(signals.get("p95")!=null) { rushP95+=number(signals.get("p95"));rushSamples++; }rushErr+=number(signals.get("errPct"));rushErrSamples++; }
        for(String name:List.of("rps","p95","p99","poolPct","lockWaits")) {
            Object value=signals.get(name);String key=name+"Max";
            if(value!=null && (!peaks.containsKey(key) || number(value)>number(peaks.get(key)))) {
                peaks.put(key,value);
                // 최대 풀 사용률이 순간값인지 실제 포화인지 보도록 그 초의 풀 대기 수를 같이 남긴다 (추가 필드)
                if(name.equals("poolPct")) peaks.put("poolPendingAtMax",signals.get("poolPending"));
            }
        }
        var compactEndpoints=new TreeMap<String,Object>();endpoints.forEach((key,value) -> {
            var e=object(value);var l=object(e.get("latency"));var v=new LinkedHashMap<String,Object>();v.put("rps",e.get("rps"));v.put("p50",l.get("p50"));v.put("p95",l.get("p95"));v.put("p99",l.get("p99"));v.put("err",e.get("errorClasses"));compactEndpoints.put(key,v);
        });
        var serverSeries=new LinkedHashMap<String,Object>();serverSeries.put("endpoints",compactEndpoints);serverSeries.put("errorClasses",total.get("errorClasses"));
        var acquire=object(p.get("acquireMs"));var poolSeries=new LinkedHashMap<String,Object>();poolSeries.put("active",p.get("active"));poolSeries.put("pending",p.get("pending"));poolSeries.put("acquireP95",acquire.get("p95"));serverSeries.put("pool",poolSeries);
        serverSeries.put("db",db);serverSeries.put("http",http);serverSeries.put("jvm",server.get("jvm"));serverSeries.put("seatsCache",server.get("seatsCache"));
        var pgMetrics=object(server.get("pg"));var pgSeries=new LinkedHashMap<String,Object>();pgSeries.put("confirmP95",object(pgMetrics.get("confirm")).get("p95"));pgSeries.put("timeouts",pgMetrics.get("timeouts"));serverSeries.put("pg",pgSeries);serverSeries.put("notifier",server.get("notifier"));
        var queue=new LinkedHashMap<String,Object>();
        var statusEndpoint=object(object(externalQueue.get("endpoints")).get("queue.status"));
        for(String k:List.of("waiting","active","busy","slotsBusyOverTtl","expiredByBusyCap","admittedThisTick")) queue.put(k,externalQueue.get(k));
        queue.put("statusRps",statusEndpoint.get("rps"));queue.put("statusP95",object(statusEndpoint.get("latency")).get("p95"));
        var clientP95=new TreeMap<String,Object>();client.histogram().endpoints().forEach((k,v) -> clientP95.put(k,v.latency().p95()));
        var clientSeries=new LinkedHashMap<String,Object>();clientSeries.put("rps",client.histogram().total().rps());clientSeries.put("p95",clientP95);clientSeries.put("timeout",client.histogram().total().errorClasses().get("timeout"));clientSeries.put("transport",client.histogram().total().errorClasses().get("transport"));clientSeries.put("rateLimited",client.histogram().total().errorClasses().get("rateLimited"));
        var series=new LinkedHashMap<String,Object>();series.put("t",t);series.put("wallMs",elapsedMs);series.put("phase",next);series.put("signals",signals);series.put("server",serverSeries);series.put("queue",queue);series.put("client",clientSeries);
        series.put("mockPg",Map.of("authInflight",live.pg().getOrDefault("authInflight",0L),"confirmInflight",pg.getOrDefault("confirmInflight",0)));series.put("users",live.users());
        var seats=new LinkedHashMap<String,Object>();for(var k:Map.of("A","availableSeats","H","heldSeats","D","pendingDepositSeats","R","returnPendingSeats","S","soldSeats").entrySet()) seats.put(k.getKey(),server.get(k.getValue()));series.put("seats",seats);
        return new Frame(Collections.unmodifiableMap(signals),Collections.unmodifiableMap(series),events);
    }
    public Summary finish(Summary summary,Map<String,Object> server,RunStats.ClientMetrics client) {
        var signals=new LinkedHashMap<String,Object>(peaks);for(String key:List.of("rpsMax","p95Max","p99Max","poolPctMax","lockWaitsMax")) signals.putIfAbsent(key,null);
        signals.put("p95Rush",rushSamples==0 ? null : round(rushP95/rushSamples));signals.put("errPctRush",rushErrSamples==0 ? null : round(rushErr/rushErrSamples));
        signals.put("failPct",client.serverSent()==0 ? 0 : round(client.serverFailed()*100.0/client.serverSent()));signals.put("poolSatSec",poolSat);signals.put("sloBreachSec",sloSec);signals.put("soldOutAtSec",soldOutAt);
        var classes=RequestHistograms.zeros();var cumulative=client.histogram().cumulative();var total=object(cumulative.get("total"));object(total.get("errorClasses")).forEach((k,v) -> classes.put(k,(long)number(v)));
        return summary.withMetrics(signals,object(server.get("cumulative")),cumulative,classes);
    }
    private static Map<String,Object> event(long t,String type,Map<String,Object> fields) { var e=new LinkedHashMap<String,Object>();e.put("t",t);e.put("type",type);e.putAll(fields);return e; }
    public static double number(Object value) { return value instanceof Number n ? n.doubleValue() : 0; }
    private static double round(double n) { return Math.round(n*10)/10.0; }
    private static String level(Object n,double warn,double bad) { return n==null ? "ok" : number(n)>=bad ? "bad" : number(n)>=warn ? "warn" : "ok"; }
}
