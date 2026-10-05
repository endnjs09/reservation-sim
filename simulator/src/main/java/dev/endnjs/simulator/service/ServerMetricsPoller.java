package dev.endnjs.simulator.service;

import dev.endnjs.simulator.http.JsonCodec;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ServerMetricsPoller {
    /** 11.9: ok·lastOkAt are the spec names; connected·lastSuccessAt stay for existing readers (0장 9번). */
    public record Connection(boolean connected, String url, Instant checkedAt, Instant lastSuccessAt, String error, boolean ok, Instant lastOkAt) {
        public Connection(boolean connected, String url, Instant checkedAt, Instant lastSuccessAt, String error) { this(connected,url,checkedAt,lastSuccessAt,error,connected,lastSuccessAt); }
    }
    public record Sample(Map<String, Object> value, Connection connection) {}
    private final RunService runs;
    private final JsonCodec json = new JsonCodec();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
    private volatile Sample server = empty();
    private volatile Sample pg = empty();
    private volatile Sample queue=empty(),queueStats=empty();
    private static Sample empty() { return new Sample(Map.of(), new Connection(false, "", null, null, "연결 확인 중")); }
    public ServerMetricsPoller(RunService runs) { this.runs = runs; }
    public Sample server() { return server; }
    public Sample pg() { return pg; }
    public Sample queue() { return queue; }
    public Sample queueStats() { return queueStats; }
    @Scheduled(fixedRate = 1000) public void pollServer() {
        String target = runs.current().config().targets().server();
        Sample next = fetch(target, "/admin/metrics", server);
        if (target.equals(runs.current().config().targets().server())) { server = next;runs.connection("server",next.connection()); }
    }
    @Scheduled(fixedRate = 1000) public void pollPg() {
        String target = runs.current().config().targets().pg();
        Sample next = fetch(target, "/admin/stats", pg);
        if (target.equals(runs.current().config().targets().pg())) { pg = next;runs.connection("mockPg",next.connection()); }
    }
    @Scheduled(fixedRate=1000) public void pollQueue() {
        String target=runs.current().config().targets().queue();
        var metrics=fetch(target,"/admin/metrics",queue);
        var stats=fetch(target,"/admin/stats",queueStats);
        if(target.equals(runs.current().config().targets().queue())) {
            queue=metrics;queueStats=stats;
            runs.connection("queue",metrics.connection().connected() ? stats.connection() : metrics.connection());
        }
    }
    private Sample fetch(String target, String path, Sample previous) {
        String url = target.replaceAll("/+$", "") + path;
        // 3장: 실행 중에는 실행 기준점(anchor) 시계. 실행 기록의 preparedAt·METRICS_LOST 판단과 같은 시간축이다.
        Instant now = runs.clockNow();
        try {
            // HttpRequest.timeout 대신 future.get(900ms): 끝난 요청의 타이머가 재사용 연결을 끊는 JDK 21 문제 (docs/DECISION_CLAUDE.md)
            var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response;
            try { response = pending.get(900, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception notAnswered) { pending.cancel(true); throw notAnswered; }
            Map<String, Object> value = json.response(response.body());
            if (response.statusCode() != 200 || value.isEmpty()) throw new IllegalStateException("HTTP " + response.statusCode() + " / 유효하지 않은 계측 응답");
            return new Sample(java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(value)), new Connection(true, url, now, now, null));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            if (previous.connection().connected()) log.warn("계측 실패 시작: {} ({})", url, reason);
            return new Sample(previous.value(), new Connection(false, url, now, previous.connection().lastSuccessAt(), reason));
        }
    }
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ServerMetricsPoller.class);
    @PreDestroy public void close() { client.shutdownNow(); }
}
