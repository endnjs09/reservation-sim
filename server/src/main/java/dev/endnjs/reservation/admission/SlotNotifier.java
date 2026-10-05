package dev.endnjs.reservation.admission;

import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.sale.SaleService;
import dev.endnjs.reservation.snapshot.AvailabilityRepository;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** HTTP is performed only by this executor, after the mutation transaction commits. */
@Component
public class SlotNotifier {
    private static final long[] DELAYS={1,2,4};
    private static final class State {
        final AtomicLong queued=new AtomicLong(),sent=new AtomicLong(),retried=new AtomicLong(),dropped=new AtomicLong();
        final List<Map<String,Object>> drops=new CopyOnWriteArrayList<>();
        volatile Boolean soldOut;
    }
    private volatile State state=new State();
    private final ScheduledExecutorService executor=Executors.newScheduledThreadPool(2,r->{var thread=new Thread(r,"slot-notifier");thread.setDaemon(true);return thread;});
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
    private final String baseUrl,secret;
    private final boolean enabled;
    private final SaleService sale;
    private final KeyRegistry registry;
    private final MetricsCollector metrics;
    private final Clock clock;
    private final AvailabilityRepository availability;
    public SlotNotifier(@Value("${admission.queue-url:http://localhost:8082}") String baseUrl,
            @Value("${internal.secret:dev-internal-secret}") String secret,
            @Value("${internal.notifications-enabled:true}") boolean enabled,@Lazy SaleService sale,KeyRegistry registry,
            MetricsCollector metrics,Clock clock,AvailabilityRepository availability) {
        this.baseUrl=baseUrl;this.secret=secret;this.enabled=enabled;this.sale=sale;this.registry=registry;this.metrics=metrics;this.clock=clock;this.availability=availability;
    }
    public void notifyAfterCommit(UUID kid,String type,Instant at) {
        if(kid==null) return;
        var owner=state;String epoch=sale.runEpoch();
        afterCommit(()-> {
            if(owner!=state) return;
            if(type.equals("COMPLETED")) registry.revoke(kid);
            enqueue(owner,epoch,kid,type,at,"/internal/slots/"+kid+"/events",Map.of("type",type,"at",at.toString()));
        });
    }
    public void clearedAfterCommit(UUID kid,Instant at,JdbcTemplate jdbc) {
        if(kid==null) return;
        var users=jdbc.query("SELECT user_id FROM reservations WHERE admission_kid=? ORDER BY id DESC LIMIT 1",(rs,row)->rs.getString(1),kid);
        if(users.isEmpty()) return;
        String user=users.getFirst();
        boolean stillActive=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM reservations WHERE user_id=? AND status IN ('HELD','CONFIRMING'))",Boolean.class,user));
        if(!stillActive) {
            // Unsafe strategies can leave multiple old holds under different keys; clear all when the final hold ends.
            for(var endedKid:jdbc.query("SELECT DISTINCT admission_kid FROM reservations WHERE user_id=? AND admission_kid IS NOT NULL AND status IN ('EXPIRED','RELEASED','PAYMENT_FAILED')",(rs,row)->rs.getObject(1,UUID.class),user)) {
                notifyAfterCommit(endedKid,"HOLD_CLEARED",at);
            }
        }
    }
    private static void afterCommit(Runnable action) {
        if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        }); else action.run();
    }
    public synchronized void saleState(boolean soldOut,Instant releaseAt) { saleState(soldOut,releaseAt,sale.runEpoch(),false); }
    public synchronized void saleState(boolean soldOut,Instant releaseAt,String epoch) { saleState(soldOut,releaseAt,epoch,false); }
    private synchronized void saleState(boolean soldOut,Instant releaseAt,String epoch,boolean force) {
        var owner=state;
        if(!epoch.equals(sale.runEpoch())) return;
        if(!force && Objects.equals(owner.soldOut,soldOut)) return;
        owner.soldOut=soldOut;
        var body=new LinkedHashMap<String,Object>();body.put("soldOut",soldOut);body.put("releaseAt",releaseAt==null ? null : releaseAt.toString());
        Instant at=clock.instant();
        afterCommit(()-> { if(owner==state) enqueue(owner,epoch,null,"SALE_STATE",at,"/internal/sale-state",body); });
    }
    @Scheduled(fixedDelay=5000,initialDelay=5000) public void refreshSaleState() {
        String epoch=sale.runEpoch();var current=availability.read();saleState(current.soldOut(),current.releaseAt(),epoch,true);
    }
    private void enqueue(State owner,String epoch,UUID kid,String type,Instant at,String path,Map<String,Object> body) {
        if(!enabled) return;
        String payload=JsonMapper.builder().build().writeValueAsString(body);
        owner.queued.incrementAndGet();executor.execute(()->attempt(owner,epoch,kid,type,at,path,payload,0));
    }
    private void attempt(State owner,String epoch,UUID kid,String type,Instant at,String path,String payload,int retry) {
        if(owner!=state) { owner.queued.decrementAndGet();return; }
        if(type.equals("SALE_STATE") && !Objects.equals(owner.soldOut,JsonMapper.builder().build().readTree(payload).get("soldOut").asBoolean())) {
            owner.queued.decrementAndGet();return;
        }
        if(retry>0) owner.retried.incrementAndGet();
        try {
            // HttpRequest.timeout 대신 future.get(1초): 끝난 요청의 타이머가 재사용 연결을 끊는 JDK 21 문제 (docs/DECISION_CLAUDE.md)
            var request=HttpRequest.newBuilder(URI.create(baseUrl+path))
                    .header("Content-Type","application/json").header("X-Internal-Secret",secret).header("X-Run-Epoch",epoch)
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            var pending=http.sendAsync(request,HttpResponse.BodyHandlers.discarding());
            HttpResponse<Void> response;
            try { response=pending.get(1,TimeUnit.SECONDS); } catch(Exception notAnswered) { pending.cancel(true);throw notAnswered; }
            if(response.statusCode()<200 || response.statusCode()>=300) throw new IllegalStateException("Queue HTTP "+response.statusCode());
            owner.sent.incrementAndGet();owner.queued.decrementAndGet();
        } catch(Exception failed) {
            if(owner!=state || executor.isShutdown()) { owner.queued.decrementAndGet();return; }
            if(retry<DELAYS.length) executor.schedule(()->attempt(owner,epoch,kid,type,at,path,payload,retry+1),DELAYS[retry],TimeUnit.SECONDS);
            else {
                synchronized(this) {
                    owner.queued.decrementAndGet();
                    if(owner!=state) return;
                    owner.dropped.incrementAndGet();metrics.increment("slotNotifyDropped");
                    var event=new LinkedHashMap<String,Object>();event.put("kid",kid==null ? null : kid.toString());event.put("type",type);event.put("at",at.toString());event.put("droppedAt",clock.instant().toString());
                    owner.drops.add(Collections.unmodifiableMap(event));
                }
            }
        }
    }
    public Map<String,Object> metrics() { var owner=state;return Map.of("queued",owner.queued.get(),"sent",owner.sent.get(),"retried",owner.retried.get(),"dropped",owner.dropped.get()); }
    public List<Map<String,Object>> droppedNotifications() { return List.copyOf(state.drops); }
    public synchronized void reset() { state=new State(); }
    @jakarta.annotation.PreDestroy public void close() { executor.shutdownNow();http.shutdownNow(); }
}
