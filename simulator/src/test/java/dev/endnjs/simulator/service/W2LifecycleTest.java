package dev.endnjs.simulator.service;

import com.sun.net.httpserver.HttpServer;
import dev.endnjs.simulator.http.JsonCodec;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class W2LifecycleTest {
    @TempDir Path temp;
    @Test void fiveSecondsOfMetricsLossStopsUsersAndStoresFailedRun() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/",x -> {
            String path=x.getRequestURI().getPath();String body=switch(path) {
                case "/actuator/health" -> "{\"status\":\"UP\"}";
                case "/admin/stats" -> "{\"config\":{\"strategy\":\"conditional\",\"dbBackstop\":true},\"saleEndAt\":\"2099-01-01T00:00:00Z\"}";
                case "/admin/metrics" -> "{\"pool\":{\"max\":20},\"http\":{\"threadsMax\":200}}";
                case "/admin/payments" -> "[]";
                case "/queue/enter", "/queue/status" -> "{\"token\":\"token\",\"status\":\"WAITING\",\"pollAfterMs\":1000}";
                default -> "{}";
            };
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,bytes.length);try(var out=x.getResponseBody()) { out.write(bytes); }
        });server.start();var service=new RunService(temp.resolve("runs").toString());
        String url="http://localhost:"+server.getAddress().getPort();var config=new JsonCodec().config("{\"users\":1,\"timeScale\":1,\"arrival\":[{\"percent\":100,\"fromSec\":0,\"toSec\":0}],\"targets\":{\"server\":\""+url+"\",\"pg\":\""+url+"\",\"queue\":\""+url+"\"}}");
        try {
            String id=service.start(config).id();long deadline=System.nanoTime()+5_000_000_000L;
            while(service.timing(id).preparedAt()==null && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(service.timing(id).preparedAt()).isNotNull();var at=java.time.Instant.now();
            service.connection("server",new ServerMetricsPoller.Connection(false,url,at,null,"offline"));
            service.connection("server",new ServerMetricsPoller.Connection(false,url,at.plusMillis(4999),null,"offline"));
            assertThat(service.current().error()).isNull();
            service.connection("server",new ServerMetricsPoller.Connection(false,url,at.plusMillis(5000),null,"offline"));
            while(service.current().running() && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(service.current().status()).isEqualTo("FAILED");assertThat(service.store().get(id)).containsEntry("statusReason","METRICS_LOST");
            assertThat(service.summary(id).outcomes().get("incomplete")).isEqualTo(1);
        } finally { service.close();server.stop(0); }
    }
    @Test void targetDownReturns409WithoutCreatingRun() throws Exception {
        var service=new RunService(temp.resolve("runs").toString());
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);server.createContext("/actuator/health",x -> { x.sendResponseHeaders(503,-1);x.close(); });server.start();
        String url="http://localhost:"+server.getAddress().getPort();var config=new JsonCodec().config("{\"targets\":{\"server\":\""+url+"\",\"pg\":\""+url+"\",\"queue\":\""+url+"\"}}");
        try {
            assertThatThrownBy(() -> service.start(config)).isInstanceOfSatisfying(RunService.TargetDown.class,failure -> { assertThat(failure.status).isEqualTo(409);assertThat(failure.target).isEqualTo("server"); });
            assertThat((List<?>)service.store().list().get("runs")).isEmpty();assertThat(service.current().id()).isNull();
        } finally { service.close();server.stop(0); }
    }
    @Test void failedResetCreatesFailedRecordAndNeverStartsUsers() throws Exception {
        var userCalls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/",x -> {
            String path=x.getRequestURI().getPath();int status=200;String body="{}";
            switch(path) {
                case "/actuator/health" -> body="{\"status\":\"UP\"}";
                case "/admin/reset" -> status=500;
                case "/admin/payments" -> body="[]";
                default -> { if(!path.startsWith("/admin/")) userCalls.incrementAndGet(); }
            }
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,bytes.length);try(var out=x.getResponseBody()) { out.write(bytes); }
        });server.start();var service=new RunService(temp.resolve("runs").toString());
        String url="http://localhost:"+server.getAddress().getPort();var config=new JsonCodec().config("{\"users\":2,\"targets\":{\"server\":\""+url+"\",\"pg\":\""+url+"\",\"queue\":\""+url+"\"}}");
        try {
            String id=service.start(config).id();long deadline=System.nanoTime()+5_000_000_000L;
            while(service.current().running() && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(service.current().status()).isEqualTo("FAILED");assertThat(service.store().get(id)).containsEntry("statusReason","RESET_FAILED");assertThat(userCalls.get()).isZero();assertThat(service.summary(id).requests().sent()).isZero();
        } finally { service.close();server.stop(0); }
    }
}
