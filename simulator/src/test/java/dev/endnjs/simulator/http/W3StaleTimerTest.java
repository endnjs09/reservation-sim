package dev.endnjs.simulator.http;

import com.sun.net.httpserver.HttpServer;
import dev.endnjs.simulator.engine.HttpTransport;
import dev.endnjs.simulator.engine.RunConfig;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3 진단: 끝난 요청의 응답 타이머(HttpRequest.timeout)가 같은 연결을 재사용한 다음 요청을 취소하면
 * 서버가 정상 응답했는데도 클라이언트에 timeout/연결 오류가 기록된다. 전송은 자기 future.get(timeout)으로만 시간을 잰다.
 */
class W3StaleTimerTest {
    private HttpServer server;
    private final AtomicInteger connections = new AtomicInteger();

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private String start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1024); // 동시 접속이 몰려도 connect가 밀리지 않게
        server.createContext("/fast", exchange -> respond(exchange, 0));
        server.createContext("/slow", exchange -> respond(exchange, 1000));
        server.createContext("/medium", exchange -> respond(exchange, 250));
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(64));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, long delayMs) throws java.io.IOException {
        try { Thread.sleep(delayMs); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }

    @Test void finishedRequestsTimerDoesNotCancelTheNextRequestOnTheSameConnection() throws Exception {
        String base = start();
        try (var transport = new JdkHttpTransport(new RunConfig.Targets(base, base, base), new JsonCodec())) {
            var fast = transport.exchange(new HttpTransport.Request(HttpTransport.Target.SERVER, "GET", "/fast", Map.of(), null, Duration.ofMillis(500)));
            assertThat(fast.status()).isEqualTo(200);
            // 같은 연결 재사용. 앞 요청의 500ms 타이머가 남아 있으면 이 요청은 0.5초쯤 끊긴다.
            var slow = transport.exchange(new HttpTransport.Request(HttpTransport.Target.SERVER, "GET", "/slow", Map.of(), null, Duration.ofSeconds(5)));
            assertThat(slow.status()).isEqualTo(200);
        }
    }
    /** 실제 실행과 비슷하게 짧은 timeout의 빠른 요청과 느린 요청을 여러 스레드가 섞어 보낸다. */
    @Test void manyConcurrentShortTimersNeverBreakLaterRequests() throws Exception {
        String base = start();
        var failures = new AtomicInteger();
        var causes = new java.util.concurrent.ConcurrentHashMap<String,Integer>();
        try (var transport = new JdkHttpTransport(new RunConfig.Targets(base, base, base), new JsonCodec());
             var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < 64; w++) workers.submit(() -> {
                for (int i = 0; i < 40; i++) {
                    // 빠른 요청은 부하로 150ms를 넘으면 정당하게 timeout될 수 있어 세지 않는다.
                    try { transport.exchange(new HttpTransport.Request(HttpTransport.Target.SERVER, "GET", "/fast", Map.of(), null, Duration.ofMillis(150))); }
                    catch (Exception expectedUnderLoad) { }
                    // 서버가 250ms에 응답하는 요청은 5초 안에 반드시 성공해야 한다.
                    try { transport.exchange(new HttpTransport.Request(HttpTransport.Target.SERVER, "GET", "/medium", Map.of(), null, Duration.ofSeconds(5))); }
                    catch (Exception failed) { failures.incrementAndGet(); causes.merge(rootCause(failed), 1, Integer::sum); }
                }
                return null;
            });
        }
        assertThat(failures.get()).as("원인 %s", causes).isZero();
    }
    private static String rootCause(Throwable failure) {
        var names = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) names.append(cause.getClass().getSimpleName()).append(cause.getCause() == null ? ":" + cause.getMessage() : ">");
        return names.toString();
    }
    @Test void ownDeadlineStillTimesOut() throws Exception {
        String base = start();
        try (var transport = new JdkHttpTransport(new RunConfig.Targets(base, base, base), new JsonCodec())) {
            long started = System.nanoTime();
            var failure = org.assertj.core.api.Assertions.catchThrowable(() -> transport.exchange(
                    new HttpTransport.Request(HttpTransport.Target.SERVER, "GET", "/slow", Map.of(), null, Duration.ofMillis(300))));
            assertThat(failure).isInstanceOf(java.io.IOException.class);
            assertThat(dev.endnjs.simulator.engine.RunStatsAccess.timeout(failure)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(900));
        }
    }
}
