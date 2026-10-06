package dev.endnjs.queue;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QueueStateTest {
    private static final Instant NOW=Instant.parse("2026-10-04T00:00:00Z");
    private final TestClock clock=new TestClock();
    private final QueueState queue=new QueueState(clock,"test-secret",200,20,420,510,false);
    private UUID enter(String user) { return (UUID)queue.enter(user).get("token"); }
    private UUID kid(UUID token) {
        var key=(String)queue.status(token).get("admissionKey");var payload=new String(Base64.getUrlDecoder().decode(key.split("\\.")[1]),java.nio.charset.StandardCharsets.UTF_8);
        return UUID.fromString(new tools.jackson.databind.json.JsonMapper().readTree(payload).get("kid").asText());
    }
    private long counter(String name) { return ((Number)((Map<?,?>)queue.stats().get("counters")).get(name)).longValue(); }
    @Test void capacityRateAndSequenceRemainAtomicAcrossAThousandUsers() {
        var tokens=new ArrayList<UUID>();for(int i=0;i<1000;i++) tokens.add(enter("u-"+i));
        for(int cycle=0;cycle<10;cycle++) { clock.advance(1);assertThat(queue.tick()).isEqualTo(20);assertThat(queue.stats().get("ADMITTED")).isEqualTo((cycle+1)*20L); }
        clock.advance(1);assertThat(queue.tick()).isZero();assertThat(queue.snapshot()).containsEntry("activeMax",200L).containsEntry("admittedPerTickMax",20L);
        for(int i=0;i<200;i++) assertThat(queue.status(tokens.get(i)).get("status")).isEqualTo(QueueState.Status.ADMITTED);
        assertThat(queue.status(tokens.get(200))).containsEntry("position",1L);
        var history=(List<?>)queue.snapshot().get("admissions");var first=(Map<?,?>)history.getFirst();assertThat(first.get("seqFrom")).isEqualTo(1L);assertThat(first.get("seqTo")).isEqualTo(20L);
    }
    @Test void simultaneousEntriesPreserveUniqueUserTokensAndFifoAdmission() throws Exception {
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var calls=new ArrayList<java.util.concurrent.Future<UUID>>();
            for(int i=0;i<2000;i++) {
                String user="concurrent-"+(i%1000);
                calls.add(executor.submit(() -> { start.await();return enter(user); }));
            }
            start.countDown();var unique=new HashSet<UUID>();
            for(var call:calls) unique.add(call.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(unique).hasSize(1000);assertThat(queue.stats().get("WAITING")).isEqualTo(1000L);
        }
        for(int tick=0;tick<10;tick++) { clock.advance(1);assertThat(queue.tick()).isEqualTo(20); }
        var tokens=(List<Map<String,Object>>)queue.snapshot().get("tokens");
        assertThat(tokens.stream().filter(t -> t.get("status")==QueueState.Status.ADMITTED).map(t -> (Long)t.get("seq")))
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1,200).boxed().toList());
        assertThat(queue.snapshot()).containsEntry("activeMax",200L).containsEntry("admittedPerTickMax",20L);
    }
    @Test void healthyBusyBeyondTtlIsObservableButIsNotABusyCapExpiration() {
        UUID token=enter("owner");clock.advance(1);queue.tick();UUID kid=kid(token);
        assertThat(queue.slotEvent(kid,QueueState.Event.HOLD_ACTIVE,clock.instant(),null)).isFalse();
        clock.advance(420);queue.tick();assertThat(queue.stats()).containsEntry("ADMITTED",1L).containsEntry("slotsBusyOverTtl",1L);assertThat(counter("expiredByBusyCap")).isZero();
        queue.slotEvent(kid,QueueState.Event.HOLD_CLEARED,clock.instant(),null);queue.tick();
        assertThat(queue.status(token).get("status")).isEqualTo(QueueState.Status.EXPIRED);assertThat(counter("expiredByBusyCap")).isZero();
    }
    @Test void lostClearIsReclaimedExactlyAtBusyCapAndRetainsKidUserAndTime() {
        UUID token=enter("owner");clock.advance(1);queue.tick();UUID kid=kid(token);queue.slotEvent(kid,QueueState.Event.HOLD_ACTIVE,clock.instant(),null);
        clock.advance(929);queue.tick();assertThat(counter("expiredByBusyCap")).isZero();clock.advance(1);queue.tick();
        assertThat(counter("expiredByBusyCap")).isEqualTo(1);assertThat(counter("expired")).isEqualTo(1);
        assertThat(queue.status(token).get("status")).isEqualTo(QueueState.Status.EXPIRED);
        assertThat((List<?>)queue.snapshot().get("busyCapExpirations")).hasSize(1);
        var expiration=(Map<?,?>)((List<?>)queue.snapshot().get("busyCapExpirations")).getFirst();assertThat(expiration.get("kid")).isEqualTo(kid);assertThat(expiration.get("userId")).isEqualTo("owner");assertThat(expiration.get("at")).isEqualTo(clock.instant());
    }
    @Test void completionReturnsCapacityAndOldEventsCannotReviveTheSlot() {
        queue.reset(new QueueConfig(1,20,420,510,1,NOW.plusSeconds(1200),false,"run",null));
        UUID first=enter("first"),next=enter("next");clock.advance(1);queue.tick();UUID kid=kid(first);
        queue.slotEvent(kid,QueueState.Event.COMPLETED,clock.instant(),"run");assertThat(queue.slotEvent(kid,QueueState.Event.HOLD_ACTIVE,clock.instant().plusSeconds(1),"run")).isTrue();
        clock.advance(1);queue.tick();assertThat(queue.status(next).get("status")).isEqualTo(QueueState.Status.ADMITTED);assertThat(queue.status(first).get("status")).isEqualTo(QueueState.Status.COMPLETED);
    }
    @Test void eventsAreOrderedAndPreviousEpochAndEqualTimestampRetriesAreIgnored() {
        UUID first=enter("first");clock.advance(1);queue.tick();UUID kid=kid(first);Instant at=clock.instant();
        queue.slotEvent(kid,QueueState.Event.HOLD_CLEARED,at,null);
        assertThat(queue.slotEvent(kid,QueueState.Event.HOLD_ACTIVE,at.minusMillis(1),null)).isTrue();
        assertThat(queue.slotEvent(kid,QueueState.Event.HOLD_ACTIVE,at,null)).isTrue();
        assertThat(queue.slotEvent(kid,QueueState.Event.HOLD_ACTIVE,at.plusSeconds(1),"old-run")).isTrue();assertThat(queue.stats().get("busy")).isEqualTo(0L);
    }
    /** 매진으로 닫을 때는 WAITING만 CLOSED(SOLD_OUT). 입장한 사람(바쁘든 아니든)은 그대로: 입장키가 계속 유효하므로 자리도 유지 (docs/DECISION_CLAUDE.md). */
    @Test void soldOutOptionClosesOnlyWaitingAndKeepsAdmittedAndAllowsReentryAfterReopen() {
        queue.reset(new QueueConfig(2,20,420,510,1,NOW.plusSeconds(1200),true,"run",null));
        UUID busy=enter("busy"),idle=enter("idle"),waiting=enter("waiting");clock.advance(1);queue.tick();queue.slotEvent(kid(busy),QueueState.Event.HOLD_ACTIVE,clock.instant(),"run");
        queue.saleState(true,"run");
        for(UUID token:List.of(busy,idle)) assertThat(queue.status(token).get("status")).isEqualTo(QueueState.Status.ADMITTED);
        assertThat(queue.status(waiting)).containsEntry("status",QueueState.Status.CLOSED).containsEntry("reason","SOLD_OUT");
        assertThat(queue.enter("new")).containsEntry("status",QueueState.Status.CLOSED);queue.saleState(false,"run");assertThat(queue.enter("new")).containsEntry("status",QueueState.Status.WAITING);
    }
    /** 매진 → 닫힘 → 매진 풀림 → 다시 열림을 반복해도 유효한 입장키를 가진 사람은 maxActive 이하. */
    @Test void validKeyHoldersNeverExceedMaxActiveAcrossSoldOutCycles() {
        queue.reset(new QueueConfig(3,20,420,510,1,NOW.plusSeconds(1200),true,"run",null));
        var expires=new HashMap<UUID,Instant>();int user=0;
        for(int cycle=0;cycle<6;cycle++) {
            for(int i=0;i<5;i++) enter("u"+(user++));
            clock.advance(1);queue.tick();
            for(var t:(List<Map<String,Object>>)queue.snapshot().get("tokens")) {
                var id=(UUID)t.get("tokenId");
                if(t.get("status")==QueueState.Status.ADMITTED && !expires.containsKey(id)) expires.put(id,(Instant)queue.status(id).get("admissionExpiresAt"));
            }
            long holders=expires.entrySet().stream().filter(e -> clock.instant().isBefore(e.getValue())).filter(e -> {
                var status=queue.status(e.getKey()).get("status");return status==QueueState.Status.ADMITTED || status==QueueState.Status.CLOSED; // CLOSED여도 입장키는 만료까지 유효
            }).count();
            assertThat(holders).as("cycle "+cycle).isLessThanOrEqualTo(3);
            queue.saleState(true,"run");queue.saleState(false,"run"); // 즉시 반환 좌석으로 매진이 풀림
        }
    }
    /** 입장한 사람의 자리는 키 만료까지 유지되고, 다시 열릴 때 실제 빈 자리만큼만 입장. */
    @Test void reopeningAdmitsOnlyIntoActuallyFreeSlots() {
        queue.reset(new QueueConfig(2,20,420,510,1,NOW.plusSeconds(1200),true,"run",null));
        UUID a=enter("a"),b=enter("b");clock.advance(1);queue.tick();
        queue.saleState(true,"run");queue.saleState(false,"run");
        UUID late=enter("late");clock.advance(1);queue.tick();
        assertThat(queue.status(late).get("status")).isEqualTo(QueueState.Status.WAITING); // 빈 자리 없음
        assertThat(queue.stats()).containsEntry("ADMITTED",2L);
        queue.leave(a);clock.advance(1);queue.tick();
        assertThat(queue.status(late).get("status")).isEqualTo(QueueState.Status.ADMITTED); // 실제로 빈 한 자리만큼
        clock.advance(420);queue.tick();
        assertThat(queue.status(b).get("status")).isEqualTo(QueueState.Status.EXPIRED); // b의 자리는 키 만료까지 유지됐다
    }
    @Test void soldOutDefaultDoesNotCloseEnterOrReentryAndWaitingResponsesHideInventory() {
        UUID token=enter("owner");queue.saleState(true,null);assertThat(queue.enter("owner")).containsEntry("token",token).containsEntry("status",QueueState.Status.WAITING);
        queue.leave(token);assertThat(queue.enter("owner")).containsEntry("status",QueueState.Status.WAITING).doesNotContainEntry("token",token);
        assertThat(queue.enter("new").keySet()).containsExactly("token","status","position","pollAfterMs","reason");
        assertThat(queue.status((UUID)queue.enter("other").get("token")).keySet()).containsExactly("status","position","pollAfterMs");
    }
    @Test void saleEndClosesBusyAndWaitingAndNewTokensAtExactBoundary() {
        UUID busy=enter("busy");clock.advance(1);queue.tick();queue.slotEvent(kid(busy),QueueState.Event.HOLD_ACTIVE,clock.instant(),null);UUID waiting=enter("waiting");
        clock.advance(1199);queue.tick();for(UUID id:List.of(busy,waiting)) assertThat(queue.status(id)).containsEntry("status",QueueState.Status.CLOSED).containsEntry("reason","SALE_ENDED");
        assertThat(queue.enter("new")).containsEntry("status",QueueState.Status.CLOSED).containsEntry("reason","SALE_ENDED");assertThat(queue.stats()).containsEntry("WAITING",0L).containsEntry("ADMITTED",0L);
    }
    @Test void scaledAdmissionAndFractionalExpiryFollowSimulationTime() {
        queue.reset(new QueueConfig(100,3,7,5,4,NOW.plusSeconds(300),false,"run",null));for(int i=0;i<20;i++) enter("u"+i);clock.advance(1);assertThat(queue.tick()).isEqualTo(12);
        clock.now=clock.now.plusMillis(1749);queue.tick();assertThat(counter("expired")).isZero();clock.now=clock.now.plusMillis(1);queue.tick();assertThat(counter("expired")).isEqualTo(12);
    }
    @Test void pollIntervalsMatchBaseBoundariesAndResetClearsForensicFacts() {
        assertThat(List.of(QueueState.pollAfter(50),QueueState.pollAfter(51),QueueState.pollAfter(300),QueueState.pollAfter(301),QueueState.pollAfter(1000),QueueState.pollAfter(1001))).containsExactly(1000,2000,2000,4000,4000,6000);
        enter("u");clock.advance(1);queue.tick();queue.reset(queue.config());assertThat(queue.snapshot().get("tokens")).isEqualTo(List.of());assertThat(queue.snapshot().get("busyCapExpirations")).isEqualTo(List.of());assertThat(counter("entered")).isZero();
    }
    private static class TestClock extends Clock {
        Instant now=NOW;void advance(long seconds) { now=now.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }public Clock withZone(ZoneId zone) { return this; }public Instant instant() { return now; }
    }
}
