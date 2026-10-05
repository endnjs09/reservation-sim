package dev.endnjs.reservation.admission;

import com.sun.net.httpserver.HttpServer;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.sale.SaleService;
import dev.endnjs.reservation.snapshot.AvailabilityRepository;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlotNotifierTest {
    private HttpServer server;
    private SlotNotifier notifier;
    private final KeyRegistry registry=new KeyRegistry();
    private final Clock clock=Clock.systemUTC();
    private final MetricsCollector metrics=new MetricsCollector(clock);
    private final AtomicInteger calls=new AtomicInteger();
    private final List<Map<String,Object>> delivered=new CopyOnWriteArrayList<>();
    private final List<Long> times=new CopyOnWriteArrayList<>();
    private int failures;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",request -> {
            assertThat(request.getRequestHeaders().getFirst("X-Internal-Secret")).isEqualTo("internal-test");
            assertThat(request.getRequestHeaders().getFirst("X-Run-Epoch")).isEqualTo("current");
            var body=JsonMapper.builder().build().readValue(request.getRequestBody(),Map.class);
            delivered.add(body);times.add(System.nanoTime());
            int count=calls.incrementAndGet();request.sendResponseHeaders(count<=failures ? 503 : 200,-1);request.close();
        });server.start();
        var sale=mock(SaleService.class);when(sale.runEpoch()).thenReturn("current");
        notifier=new SlotNotifier("http://127.0.0.1:"+server.getAddress().getPort(),"internal-test",true,sale,registry,metrics,clock,mock(AvailabilityRepository.class));
    }
    @AfterEach void cleanup() {
        if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        notifier.close();server.stop(0);
    }
    private static void await(BooleanSupplier condition,long millis) throws Exception {
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        while(!condition.getAsBoolean() && System.nanoTime()<until) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    @Test void transactionQueuesOnlyAfterCommitAndRollbackQueuesNothing() throws Exception {
        UUID kid=UUID.randomUUID();TransactionSynchronizationManager.initSynchronization();
        notifier.notifyAfterCommit(kid,"HOLD_ACTIVE",clock.instant());
        assertThat(calls.get()).isZero();assertThat(notifier.metrics().get("queued")).isEqualTo(0L);
        var callbacks=TransactionSynchronizationManager.getSynchronizations();TransactionSynchronizationManager.clearSynchronization();
        callbacks.forEach(callback->callback.afterCommit());await(()->calls.get()==1,2000);
        TransactionSynchronizationManager.initSynchronization();notifier.notifyAfterCommit(kid,"HOLD_CLEARED",clock.instant());
        TransactionSynchronizationManager.clearSynchronization();Thread.sleep(100);assertThat(calls.get()).isEqualTo(1);
    }
    @Test void completionRevokesLocallyWithoutWaitingForQueueDelivery() throws Exception {
        failures=4;UUID kid=UUID.randomUUID();notifier.notifyAfterCommit(kid,"COMPLETED",clock.instant());
        assertThat(registry.revoked(kid)).isTrue();await(()->calls.get()>=1,2000);
    }
    @Test void retriesThreeTimesAtRealOneTwoFourSecondsThenRecordsOriginalKidAndTime() throws Exception {
        failures=4;UUID kid=UUID.randomUUID();Instant original=clock.instant();
        notifier.notifyAfterCommit(kid,"HOLD_CLEARED",original);
        await(()->((Number)notifier.metrics().get("dropped")).longValue()==1,11000);
        assertThat(calls.get()).isEqualTo(4);assertThat(notifier.metrics()).containsEntry("retried",3L).containsEntry("queued",0L).containsEntry("sent",0L);
        assertThat(metrics.counters()).containsEntry("slotNotifyDropped",1L);
        assertThat(notifier.droppedNotifications()).singleElement().satisfies(drop -> assertThat(drop).containsEntry("kid",kid.toString()).containsEntry("type","HOLD_CLEARED").containsEntry("at",original.toString()).containsKey("droppedAt"));
        for(var body:delivered) assertThat(body).containsEntry("at",original.toString()).containsEntry("type","HOLD_CLEARED");
        for(int i=1;i<times.size();i++) assertThat((times.get(i)-times.get(i-1))/1_000_000).isGreaterThanOrEqualTo(new long[]{950,1950,3950}[i-1]);
    }
    @Test void successfulRetryLeavesNoDroppedEvidence() throws Exception {
        failures=1;notifier.notifyAfterCommit(UUID.randomUUID(),"HOLD_ACTIVE",clock.instant());
        await(()->((Number)notifier.metrics().get("sent")).longValue()==1,4000);
        assertThat(notifier.metrics()).containsEntry("retried",1L).containsEntry("queued",0L).containsEntry("dropped",0L);assertThat(notifier.droppedNotifications()).isEmpty();
    }
    @Test void resetDiscardsOldRetryAndDoesNotPolluteNewRunCounters() throws Exception {
        failures=4;notifier.notifyAfterCommit(UUID.randomUUID(),"HOLD_ACTIVE",clock.instant());await(()->calls.get()==1,2000);
        notifier.reset();metrics.reset();Thread.sleep(1200);
        assertThat(calls.get()).isEqualTo(1);assertThat(notifier.metrics()).containsEntry("queued",0L).containsEntry("sent",0L).containsEntry("retried",0L).containsEntry("dropped",0L);
        assertThat(notifier.droppedNotifications()).isEmpty();assertThat(metrics.counters().get("slotNotifyDropped")).isZero();
    }
}
