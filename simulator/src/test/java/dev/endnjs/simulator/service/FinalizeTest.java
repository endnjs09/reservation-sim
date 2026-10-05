package dev.endnjs.simulator.service;

import com.sun.net.httpserver.HttpServer;
import dev.endnjs.simulator.http.JsonCodec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** 엔진이 끝난 뒤: 남은 HELD·CONFIRMING·PENDING_DEPOSIT이 없으면 고정 대기 없이 마무리, 마지막 서버 상태로 SALE_ENDED 기록. */
class FinalizeTest {
    @TempDir Path temp;
    /** 사용자 1명은 대기열에서 바로 SALE_ENDED. held가 true인 동안 서버는 HELD 1건을 보고한다. */
    private HttpServer server(AtomicBoolean held) throws Exception { return server(held,null); }
    /** holdAfterEngine이 있으면: 사용자가 끝난 뒤 첫 /admin/metrics(= 엔진 종료 후 마지막 표본)부터 HELD 1건을 보고한다. 엔진 자체의 마무리 대기를 지난 뒤에 남은 선점을 흉내 낸다. */
    private HttpServer server(AtomicBoolean held,AtomicBoolean holdAfterEngine) throws Exception {
        var userDone=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/",x -> {
            String path=x.getRequestURI().getPath();int status=200;String body=switch(path) {
                case "/actuator/health" -> "{\"status\":\"UP\"}";
                case "/admin/stats" -> "{\"config\":{\"strategy\":\"conditional\",\"dbBackstop\":true},\"saleEndAt\":\"2000-01-01T00:00:00Z\","
                        +"\"reservations\":{\"HELD\":"+(held.get() ? 1 : 0)+",\"CONFIRMING\":0,\"PENDING_DEPOSIT\":0},\"releaseBatches\":{\"nextReleaseAt\":null}}";
                case "/admin/metrics" -> { if(holdAfterEngine!=null && userDone.get() && holdAfterEngine.compareAndSet(true,false)) held.set(true);yield "{\"phase\":\"ENDED\",\"pool\":{\"max\":20},\"http\":{\"threadsMax\":200}}"; }
                case "/admin/payments" -> "[]";
                case "/queue/enter" -> { userDone.set(true);status=409;yield "{\"code\":\"SALE_ENDED\"}"; }
                default -> "{}";
            };
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,bytes.length);try(var out=x.getResponseBody()) { out.write(bytes); }
        });
        server.start();return server;
    }
    private String config(HttpServer server) {
        String url="http://localhost:"+server.getAddress().getPort();
        // 최대 대기 = holdTtlSec + confirmDeadlineSec + 5 = 30초 (1배속)
        return "{\"users\":1,\"timeScale\":1,\"holdTtlSec\":20,\"confirmDeadlineSec\":5,\"saleDurationSec\":60,\"arrival\":[{\"percent\":100,\"fromSec\":0,\"toSec\":0}],"
                +"\"targets\":{\"server\":\""+url+"\",\"pg\":\""+url+"\",\"queue\":\""+url+"\"}}";
    }
    private static void await(java.util.function.BooleanSupplier done,long millis) throws InterruptedException {
        long deadline=System.nanoTime()+millis*1_000_000;while(!done.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(20);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> snapshot(String id) throws Exception {
        return (Map<String,Object>)new JsonCodec().decode(Files.readString(temp.resolve("runs").resolve(id).resolve("snapshot.json")));
    }
    @Test void nothingLeftToSettleFinishesWithoutTheFixedWait() throws Exception {
        var server=server(new AtomicBoolean(false));var service=new RunService(temp.resolve("runs").toString());
        try {
            long started=System.nanoTime();String id=service.start(new JsonCodec().config(config(server))).id();
            await(() -> !service.current().running(),15_000);
            assertThat(service.current().status()).isEqualTo("COMPLETED");
            assertThat((System.nanoTime()-started)/1_000_000).isLessThan(10_000); // 고정 대기면 30초
            assertThat(snapshot(id)).containsEntry("quiesced",true);
        } finally { service.close();server.stop(0); }
    }
    @Test void waitsWhileHoldsRemainAndShowsFinalizing() throws Exception {
        var held=new AtomicBoolean(false);var server=server(held,new AtomicBoolean(true));var service=new RunService(temp.resolve("runs").toString());
        try {
            String id=service.start(new JsonCodec().config(config(server))).id();
            await(service::finalizing,10_000);
            assertThat(service.finalizing()).isTrue();
            assertThat(service.current().status()).isEqualTo("RUNNING"); // 상태 값은 그대로 RUNNING
            Thread.sleep(1500);
            assertThat(service.current().running()).isTrue(); // HELD가 남아 있으면 계속 기다림
            held.set(false);
            await(() -> !service.current().running(),10_000);
            assertThat(service.current().status()).isEqualTo("COMPLETED");assertThat(service.finalizing()).isFalse();
            assertThat(snapshot(id)).containsEntry("quiesced",true);
        } finally { service.close();server.stop(0); }
    }
    @Test void finalServerStateRecordsSaleEnded() throws Exception {
        var server=server(new AtomicBoolean(false));var service=new RunService(temp.resolve("runs").toString());
        try {
            String id=service.start(new JsonCodec().config(config(server))).id();
            await(() -> !service.current().running(),15_000);
            // 1초 표본(StreamService)이 한 번도 돌지 않아도, 엔진이 끝날 때 읽은 서버 phase=ENDED로 SALE_ENDED가 남는다
            assertThat(service.store().events(id)).anySatisfy(event -> assertThat(event).containsEntry("type","SALE_ENDED"));
            assertThat(service.store().series(id,null,1)).isNotEmpty();
        } finally { service.close();server.stop(0); }
    }
}
