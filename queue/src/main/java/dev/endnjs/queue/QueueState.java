package dev.endnjs.queue;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One lock protects admission, token indexes and event ordering. Status reads never scan the queue. */
@Component
public class QueueState {
    public enum Status { WAITING,ADMITTED,COMPLETED,LEFT,EXPIRED,CLOSED }
    public enum Event { HOLD_ACTIVE,HOLD_CLEARED,COMPLETED }
    private static final class Token {
        final UUID tokenId=UUID.randomUUID();final String userId;final long seq;final Instant createdAt;
        Status status=Status.WAITING;Instant admittedAt,endedAt;String endReason,admissionKey;Slot slot;
        Token(String user,long seq,Instant now) { userId=user;this.seq=seq;createdAt=now; }
        Map<String,Object> snapshot() { return fields("tokenId",tokenId,"userId",userId,"seq",seq,"status",status,"createdAt",createdAt,"admittedAt",admittedAt,"endedAt",endedAt,"endReason",endReason); }
    }
    private static final class Slot {
        final UUID keyId=UUID.randomUUID();final Token token;final Instant admittedAt,expiresAt;
        boolean busy;Instant busySince,lastAt;Event lastEvent;
        Slot(Token token,Instant at,Instant expires) { this.token=token;admittedAt=at;expiresAt=expires; }
        Map<String,Object> snapshot() { return fields("keyId",keyId,"userId",token.userId,"admittedAt",admittedAt,"expiresAt",expiresAt,"busy",busy,"busySince",busySince,"lastEventAt",lastAt); }
    }
    private final Clock clock;private final KeyIssuer issuer;
    private QueueConfig config;private long sequence,activeMax,admittedPerTickMax,admittedThisTick;
    private Instant lastTick;private boolean soldOut;
    private final Map<UUID,Token> tokens=new LinkedHashMap<>();
    private final Map<String,Token> activeUsers=new HashMap<>();
    private final NavigableMap<Long,Token> waiting=new TreeMap<>();
    private final Map<UUID,Slot> slots=new LinkedHashMap<>();
    private final Map<UUID,Long> positions=new HashMap<>();
    private final EnumMap<Status,Long> counts=new EnumMap<>(Status.class);
    private final Map<String,Long> counters=new LinkedHashMap<>();
    private final Map<String,Long> eventCounters=new LinkedHashMap<>();
    private final List<Map<String,Object>> admissions=new ArrayList<>(),busyCapExpirations=new ArrayList<>();

    public QueueState(Clock clock,@Value("${admission.key-secret}") String secret,
            @Value("${queue.max-active:200}") int max,@Value("${queue.admit-per-sec:20}") int rate,
            @Value("${queue.admission-ttl-sec:420}") int ttl,@Value("${queue.busy-max-extra-sec:510}") int extra,
            @Value("${queue.close-queue-on-sold-out:false}") boolean close) {
        this.clock=clock;issuer=new KeyIssuer(secret);
        reset(new QueueConfig(max,rate,ttl,extra,1,clock.instant().plusSeconds(1200),close,UUID.randomUUID().toString(),null));
    }
    public synchronized QueueConfig config() { return config; }
    public synchronized void reset(QueueConfig supplied) {
        config=supplied;tokens.clear();activeUsers.clear();waiting.clear();slots.clear();positions.clear();admissions.clear();busyCapExpirations.clear();
        counts.clear();for(Status status:Status.values()) counts.put(status,0L);
        counters.clear();for(String key:List.of("entered","admitted","keysIssued","expired","expiredByBusyCap","closedSoldOut","closedSaleEnded")) counters.put(key,0L);
        eventCounters.clear();for(Event event:Event.values()) eventCounters.put(event.name(),0L);eventCounters.put("ignored",0L);
        sequence=activeMax=admittedPerTickMax=admittedThisTick=0;soldOut=false;lastTick=clock.instant();
    }
    public synchronized Map<String,Object> enter(String user) {
        if(user==null || user.isBlank() || user.length()>256) throw new IllegalArgumentException("Invalid userId");
        Instant now=clock.instant();closeSaleIfEnded(now);
        Token token=activeUsers.get(user);
        if(token==null) {
            token=new Token(user,++sequence,now);tokens.put(token.tokenId,token);counts.merge(Status.WAITING,1L,Long::sum);increment("entered");
            waiting.put(token.seq,token);activeUsers.put(user,token);positions.put(token.tokenId,(long)waiting.size());
            if(!now.isBefore(config.saleEndAt())) close(token,"SALE_ENDED",now);
            else if(config.closeQueueOnSoldOut() && soldOut) close(token,"SOLD_OUT",now);
        }
        long position=token.status==Status.WAITING ? positions.getOrDefault(token.tokenId,0L) : 0;
        return fields("token",token.tokenId,"status",token.status,"position",position,"pollAfterMs",pollAfter(position),"reason",token.endReason);
    }
    public synchronized Map<String,Object> status(UUID id) {
        closeSaleIfEnded(clock.instant());Token token=tokens.get(id);if(token==null) throw new IllegalArgumentException("Unknown token");
        if(token.status==Status.WAITING) { long position=positions.getOrDefault(id,0L);return fields("status",token.status,"position",position,"pollAfterMs",pollAfter(position)); }
        if(token.status==Status.ADMITTED) return fields("status",token.status,"admissionKey",token.admissionKey,"admissionExpiresAt",token.slot.expiresAt);
        return fields("status",token.status,"reason",token.endReason);
    }
    public synchronized void leave(UUID id) {
        closeSaleIfEnded(clock.instant());Token token=tokens.get(id);
        if(token!=null && (token.status==Status.WAITING || token.status==Status.ADMITTED)) end(token,Status.LEFT,null,clock.instant());
    }
    public synchronized boolean slotEvent(UUID kid,Event event,Instant at,String epoch) {
        if(at==null) throw new IllegalArgumentException("Missing event at");
        Slot slot=slots.get(kid);
        if(slot==null || epoch!=null && !epoch.equals(config.runEpoch())
                || slot.lastAt!=null && (at.isBefore(slot.lastAt) || at.equals(slot.lastAt) && event.ordinal()<=slot.lastEvent.ordinal())) {
            eventCounters.merge("ignored",1L,Long::sum);return true;
        }
        slot.lastAt=at;slot.lastEvent=event;eventCounters.merge(event.name(),1L,Long::sum);
        switch(event) {
            case HOLD_ACTIVE -> { slot.busy=true;slot.busySince=at; }
            case HOLD_CLEARED -> { slot.busy=false;slot.busySince=null; }
            case COMPLETED -> end(slot.token,Status.COMPLETED,null,clock.instant());
        }
        return false;
    }
    public synchronized boolean saleState(boolean sold,String epoch) {
        if(epoch!=null && !epoch.equals(config.runEpoch())) return true;
        soldOut=sold;Instant now=clock.instant();closeSaleIfEnded(now);
        if(config.closeQueueOnSoldOut() && soldOut && now.isBefore(config.saleEndAt())) {
            for(Token token:new ArrayList<>(waiting.values())) close(token,"SOLD_OUT",now);
            for(Slot slot:new ArrayList<>(slots.values())) if(!slot.busy) close(slot.token,"SOLD_OUT",now);
        }
        return false;
    }
    public synchronized int tick() {
        Instant now=clock.instant();long interval=Math.max(0,Duration.between(lastTick,now).toMillis());lastTick=now;admittedThisTick=0;
        if(closeSaleIfEnded(now)) return 0;
        for(Slot slot:new ArrayList<>(slots.values())) {
            if(!now.isBefore(slot.expiresAt) && (!slot.busy || !now.isBefore(slot.expiresAt.plus(config.realDuration(config.busyMaxExtraSec()))))) {
                if(slot.busy) { increment("expiredByBusyCap");busyCapExpirations.add(fields("kid",slot.keyId,"userId",slot.token.userId,"at",now)); }
                increment("expired");end(slot.token,Status.EXPIRED,null,now);
            }
        }
        long budget=Math.min(config.maxActive()-slots.size(),(long)config.admitPerSec()*config.timeScale()*interval/1000);
        Long first=null,last=null;
        while(budget-->0 && !waiting.isEmpty()) {
            Token token=waiting.firstEntry().getValue();waiting.remove(token.seq);positions.remove(token.tokenId);
            Instant admitted=now.truncatedTo(ChronoUnit.MILLIS);Slot slot=new Slot(token,admitted,admitted.plus(config.realDuration(config.admissionTtlSec())));
            token.slot=slot;token.admittedAt=admitted;token.admissionKey=issuer.issue(slot.keyId,token.userId,admitted,slot.expiresAt,config.runEpoch());
            changeStatus(token,Status.ADMITTED);slots.put(slot.keyId,slot);increment("admitted");increment("keysIssued");admittedThisTick++;
            if(first==null) first=token.seq;last=token.seq;
        }
        positions.clear();long position=0;for(Token token:waiting.values()) positions.put(token.tokenId,++position);
        activeMax=Math.max(activeMax,slots.size());admittedPerTickMax=Math.max(admittedPerTickMax,admittedThisTick);
        admissions.add(fields("tickAt",now,"admitted",admittedThisTick,"seqFrom",first,"seqTo",last,"activeAfter",slots.size(),"intervalMs",interval));
        return (int)admittedThisTick;
    }
    public Instant now() { return clock.instant(); }
    public synchronized Map<String,Object> stats() {
        closeSaleIfEnded(clock.instant());var result=new LinkedHashMap<String,Object>();result.put("serverTime",clock.instant());counts.forEach((key,value) -> result.put(key.name(),value));
        result.put("busy",slots.values().stream().filter(s -> s.busy).count());
        result.put("slotsBusyOverTtl",slots.values().stream().filter(s -> s.busy && !clock.instant().isBefore(s.expiresAt)).count());
        var c=new LinkedHashMap<String,Object>(counters);c.put("slotEvents",Map.copyOf(eventCounters));result.put("counters",c);return result;
    }
    public synchronized Map<String,Object> snapshot() {
        closeSaleIfEnded(clock.instant());return fields("config",config,"tokens",tokens.values().stream().map(Token::snapshot).toList(),
                "slots",slots.values().stream().map(Slot::snapshot).toList(),"admissions",List.copyOf(admissions),"activeMax",activeMax,
                "admittedPerTickMax",admittedPerTickMax,"busyCapExpirations",List.copyOf(busyCapExpirations));
    }
    public synchronized long admittedThisTick() { return admittedThisTick; }
    private boolean closeSaleIfEnded(Instant now) {
        if(now.isBefore(config.saleEndAt())) return false;
        for(Token token:new ArrayList<>(activeUsers.values())) close(token,"SALE_ENDED",now);return true;
    }
    private void close(Token token,String reason,Instant now) { increment(reason.equals("SOLD_OUT") ? "closedSoldOut" : "closedSaleEnded");end(token,Status.CLOSED,reason,now); }
    private void end(Token token,Status status,String reason,Instant now) {
        waiting.remove(token.seq);positions.remove(token.tokenId);activeUsers.remove(token.userId,token);
        if(token.slot!=null) slots.remove(token.slot.keyId);changeStatus(token,status);token.endedAt=now;token.endReason=reason;
    }
    private void changeStatus(Token token,Status next) { counts.merge(token.status,-1L,Long::sum);counts.merge(next,1L,Long::sum);token.status=next; }
    private void increment(String name) { counters.merge(name,1L,Long::sum); }
    static int pollAfter(long position) { return position<=50 ? 1000 : position<=300 ? 2000 : position<=1000 ? 4000 : 6000; }
    static Map<String,Object> fields(Object... pairs) { var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2) result.put(pairs[i].toString(),pairs[i+1]);return result; }
}
