package dev.endnjs.reservation.metrics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/** Each request belongs to the registry captured at entry, even across reset. */
public final class RequestHistograms {
    private final Map<String,Window> windows=new ConcurrentHashMap<>();
    public record Latency(Double p50,Double p95,Double p99,Double max) {}
    public record Endpoint(double rps,int inflight,double avgMs,Latency latency,
            Map<String,Long> status,Map<String,Long> errors,Map<String,Long> errorClasses) {}
    public record Total(double rps,Latency latency,Map<String,Long> errorClasses) {}
    public record Sample(Map<String,Endpoint> endpoints,Total total,Map<String,Object> cumulative) {}
    public static final List<String> ERROR_CLASSES=List.of("conflict","notPayable","key","declined","client","shed","server","timeout","transport","rateLimited");
    private static final class Window {
        final Recorder recorder=new Recorder(1,60_000_000,3);
        final Histogram cumulative=new Histogram(1,60_000_000,3);
        final AtomicInteger inflight=new AtomicInteger();
        final Map<String,Long> status=new LinkedHashMap<>(),errors=new LinkedHashMap<>(),classes=new LinkedHashMap<>();
        final Map<String,Long> cumulativeClasses=new LinkedHashMap<>();
        long nanos;
        synchronized void record(long elapsed,int code,String error) {
            recorder.recordValue(Math.clamp(elapsed/1000,1L,60_000_000L)); nanos+=elapsed;
            if(code>0) status.merge(code>=200 && code<300 ? "2xx" : Integer.toString(code),1L,Long::sum);
            if(error!=null) errors.merge(error,1L,Long::sum);
            String type=classify(code,error);
            if(type!=null) { classes.merge(type,1L,Long::sum); cumulativeClasses.merge(type,1L,Long::sum); }
        }
    }
    public final class Ticket {
        private final Window window; private final long start=System.nanoTime();
        private Ticket(Window window) { this.window=window; window.inflight.incrementAndGet(); }
        public void finish(int status,String code) { finishElapsed(System.nanoTime()-start,status,code); }
        public void finishElapsed(long nanos,int status,String code) {
            window.record(Math.max(0,nanos),status,code);window.inflight.decrementAndGet();
        }
    }
    public void register(String name) { windows.computeIfAbsent(name,k -> new Window()); }
    public Ticket started(String name) { return new Ticket(windows.computeIfAbsent(name,k -> new Window())); }
    public synchronized Sample sample(double seconds) {
        var endpoints=new TreeMap<String,Endpoint>();var cumulative=new TreeMap<String,Object>();
        var total=new Histogram(1,60_000_000,3);var cumulativeTotal=new Histogram(1,60_000_000,3);
        var classes=zeros();var cumulativeClasses=zeros();
        windows.forEach((name,w) -> { synchronized(w) {
            Histogram h=w.recorder.getIntervalHistogram();w.cumulative.add(h);total.add(h);cumulativeTotal.add(w.cumulative);
            endpoints.put(name,new Endpoint(h.getTotalCount()/Math.max(.001,seconds),w.inflight.get(),
                    h.getTotalCount()==0 ? 0 : w.nanos/1_000_000.0/h.getTotalCount(),latency(h),Map.copyOf(w.status),Map.copyOf(w.errors),Map.copyOf(w.classes)));
            cumulative.put(name,cumulative(w.cumulative,w.cumulativeClasses));
            merge(classes,w.classes);merge(cumulativeClasses,w.cumulativeClasses);
            w.nanos=0;w.status.clear();w.errors.clear();w.classes.clear();
        }});
        cumulative.put("total",cumulative(cumulativeTotal,cumulativeClasses));
        return new Sample(Collections.unmodifiableMap(endpoints),new Total(total.getTotalCount()/Math.max(.001,seconds),latency(total),Map.copyOf(classes)),Collections.unmodifiableMap(cumulative));
    }
    private static Map<String,Object> cumulative(Histogram h,Map<String,Long> classes) {
        var l=latency(h);var result=new LinkedHashMap<String,Object>();
        result.put("count",h.getTotalCount());result.put("p50",l.p50());result.put("p95",l.p95());result.put("p99",l.p99());result.put("max",l.max());result.put("errorClasses",Map.copyOf(classes));
        return Collections.unmodifiableMap(result);
    }
    public static Latency latency(Histogram h) {
        return h.getTotalCount()==0 ? new Latency(null,null,null,null) : new Latency(ms(h.getValueAtPercentile(50)),ms(h.getValueAtPercentile(95)),ms(h.getValueAtPercentile(99)),ms(h.getMaxValue()));
    }
    private static double ms(long micros) { return Math.round(micros/100.0)/10.0; }
    public static Map<String,Long> zeros() { var map=new LinkedHashMap<String,Long>();ERROR_CLASSES.forEach(k -> map.put(k,0L));return map; }
    private static void merge(Map<String,Long> to,Map<String,Long> from) { from.forEach((k,v) -> to.merge(k,v,Long::sum)); }
    public static String classify(int status,String code) {
        if("timeout".equals(code) || "transport".equals(code)) return code;
        if(status==429 && "RATE_LIMITED".equals(code)) return "rateLimited"; // 좌석 조회 새로고침 제한: 에러율에 넣지 않음
        if(status==429 || status==503) return "shed";
        if(status>=500) return "server";
        if(status==402) return "declined";
        if(status==403 && Set.of("KEY_INVALID","KEY_EXPIRED","KEY_REVOKED").contains(code==null ? "" : code)) return "key";
        if(status==409 && Set.of("SEAT_UNAVAILABLE","USER_ALREADY_HOLDING","USER_ALREADY_PURCHASED").contains(code==null ? "" : code)) return "conflict";
        if(status==409 && Set.of("RESERVATION_NOT_PAYABLE","DEPOSIT_NOT_ACCEPTABLE","RESERVATION_NOT_CANCELABLE","SALE_ENDED").contains(code==null ? "" : code)) return "notPayable";
        return status>=400 && status<500 ? "client" : null;
    }
}
