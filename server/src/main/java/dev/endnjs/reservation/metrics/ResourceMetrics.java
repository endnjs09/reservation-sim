package dev.endnjs.reservation.metrics;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/** Installs the Hikari tracker before pool initialization; reset swaps its recording state. */
@Component
public class ResourceMetrics implements BeanPostProcessor, ApplicationContextAware {
    private volatile RequestHistograms acquisitions=new RequestHistograms();
    private volatile LongAdder timeouts=new LongAdder();
    private ApplicationContext context;
    private long previousGc=gcTime();
    @Override public void setApplicationContext(ApplicationContext context) { this.context=context; }
    @Override public Object postProcessAfterInitialization(Object bean,String name) {
        if(bean instanceof HikariDataSource ds && ds.getMetricsTrackerFactory()==null) {
            ds.setMetricsTrackerFactory((pool,stats) -> new IMetricsTracker() {
                @Override public void recordConnectionAcquiredNanos(long nanos) {
                    acquisitions.started("acquire").finishElapsed(nanos,0,null);
                }
                @Override public void recordConnectionTimeout() { timeouts.increment(); }
            });
        }
        return bean;
    }
    public record Acquisition(Map<String,Object> latency,long timeouts) {}
    public Acquisition acquisition() {
        var h=acquisitions.sample(1).total().latency();var result=new LinkedHashMap<String,Object>();
        result.put("p95",h.p95());result.put("max",h.max());return new Acquisition(Collections.unmodifiableMap(result),timeouts.sum());
    }
    public synchronized void reset() { acquisitions=new RequestHistograms();timeouts=new LongAdder();previousGc=gcTime(); }
    public Map<String,Object> http() {
        var result=new LinkedHashMap<String,Object>();result.put("threadsBusy",null);result.put("threadsMax",null);result.put("virtualThreads",false);
        if(context instanceof org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext web && web.getWebServer() instanceof org.springframework.boot.tomcat.TomcatWebServer tomcat) {
            int busy=0,max=0;
            for(var connector:tomcat.getTomcat().getService().findConnectors()) {
                var executor=connector.getProtocolHandler().getExecutor();
                if(executor instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor pool) { busy+=pool.getActiveCount();max+=pool.getMaximumPoolSize(); }
                else if(executor instanceof java.util.concurrent.ThreadPoolExecutor pool) { busy+=pool.getActiveCount();max+=pool.getMaximumPoolSize(); }
                else if(executor instanceof org.apache.catalina.core.StandardThreadExecutor pool) { busy+=pool.getActiveCount();max+=pool.getMaxThreads(); }
                else if(executor instanceof org.apache.tomcat.util.threads.VirtualThreadExecutor) result.put("virtualThreads",true);
            }
            result.put("threadsBusy",busy);if(max>0) result.put("threadsMax",max);
        }
        return Collections.unmodifiableMap(result);
    }
    public synchronized Map<String,Object> jvm() {
        var memory=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();long gc=gcTime();
        var result=Map.<String,Object>of("heapUsedMb",Math.round(memory.getUsed()/104857.6)/10.0,
                "heapMaxMb",Math.round(memory.getMax()/104857.6)/10.0,"gcPauseMs",Math.max(0,gc-previousGc),
                "liveThreads",ManagementFactory.getThreadMXBean().getThreadCount());previousGc=gc;return result;
    }
    private static long gcTime() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0,b.getCollectionTime())).sum(); }
}
