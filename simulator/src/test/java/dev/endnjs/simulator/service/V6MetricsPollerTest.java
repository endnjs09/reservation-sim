package dev.endnjs.simulator.service;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.endnjs.simulator.http.JsonCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class V6MetricsPollerTest {
    @Test void normalNullableMetricsConnectAndFailedPollRetainsThatSnapshot() throws Exception {
        var failing=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/admin/metrics",exchange -> {
            String body=failing.get() ? "{\"message\":\"unavailable\"}"
                    : "{\"at\":\"2026-10-03T05:00:00Z\",\"phase\":\"OPEN\",\"seatMap\":\"AA\",\"releaseAt\":null,\"lastReopenAt\":null}";
            var bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(failing.get()?503:200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();
        var runs=mock(RunService.class);
        var config=new JsonCodec().config("{\"targets\":{\"server\":\"http://localhost:"+server.getAddress().getPort()+"\"}}");
        when(runs.current()).thenReturn(new RunService.Current(null,"idle",false,config,null,null,null,null));
        var poller=new ServerMetricsPoller(runs);
        try {
            poller.pollServer();var first=poller.server();
            assertThat(first.connection().connected()).isTrue();
            assertThat(first.value()).containsEntry("phase","OPEN").containsEntry("releaseAt",null).containsEntry("lastReopenAt",null);
            assertThatThrownBy(()->first.value().put("phase","ENDED")).isInstanceOf(UnsupportedOperationException.class);
            failing.set(true);poller.pollServer();var second=poller.server();
            assertThat(second.connection().connected()).isFalse();
            assertThat(second.connection().lastSuccessAt()).isEqualTo(first.connection().lastSuccessAt());
            assertThat(second.value()).isEqualTo(first.value());
        } finally {poller.close();server.stop(0);}
    }
}
