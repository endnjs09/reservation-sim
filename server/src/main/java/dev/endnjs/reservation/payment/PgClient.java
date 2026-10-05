package dev.endnjs.reservation.payment;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.annotation.PreDestroy;
import dev.endnjs.reservation.metrics.MetricsCollector;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class PgClient {
    public enum Status { AUTHORIZED, DONE, DECLINED, CANCELED, MISSING }
    public record Approval(boolean approved, String failReason) {}
    private final HttpClient http;
    private final ObjectMapper json;
    private final PgProperties config;
    private final String baseUrl;
    private final MetricsCollector metrics;
    public PgClient(ObjectMapper json, PgProperties config, MetricsCollector metrics) {
        this.json = json; this.config = config; this.baseUrl = config.baseUrl().replaceAll("/+$", "");
        this.metrics = metrics;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    public Approval confirm(String key, UUID orderId, int amount) {
        return approve(key,orderId,amount);
    }
    private Approval approve(String key, UUID orderId, int amount) {
        var response = request("POST", "/payments/confirm", Map.of("paymentKey", key, "orderId", orderId, "amount", amount));
        if (response.statusCode() == 200 && "DONE".equals(field(response, "status"))) return new Approval(true, null);
        if (response.statusCode() >= 400 && response.statusCode() < 500) {
            String code = field(response, "code");
            if (code == null || code.isBlank()) code = "PG_DECLINED";
            return new Approval(false, code.substring(0, Math.min(64, code.length())));
        }
        throw new PgUnavailableException("PG approval result unknown");
    }

    public Status status(String key) {
        var response = request("GET", path(key), null);
        if (response.statusCode() == 404) return Status.MISSING;
        if (response.statusCode() != 200) throw new PgUnavailableException("PG lookup failed");
        try {
            Status status = Status.valueOf(field(response, "status"));
            if (status == Status.MISSING) throw new IllegalArgumentException("Unexpected PG status");
            return status;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new PgUnavailableException("Invalid PG lookup response", exception);
        }
    }

    public void cancel(String key) {
        var response = request("POST", path(key) + "/cancel", Map.of());
        if (response.statusCode() != 200 || !"CANCELED".equals(field(response, "status"))) {
            throw new PgUnavailableException("PG cancellation not confirmed");
        }
    }

    private HttpResponse<String> request(String method, String path, Object body) {
        // HttpRequest.timeout은 쓰지 않는다: 끝난 요청의 응답 타이머가 재사용 연결의 다음 요청을 끊는 JDK 21 문제
        // (docs/DECISION_CLAUDE.md). 시간 제한은 아래 get(requestTimeoutMs)가 맡는다.
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        var measurement=metrics.pgStarted(path.equals("/payments/confirm") ? "confirm" : method.equals("GET") ? "query" : "cancel");
        int outcome=0;String failureCode=null;
        CompletableFuture<HttpResponse<String>> response = http.sendAsync(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            // Bound the whole exchange, including receipt of the response body.
            var result=response.get(config.requestTimeoutMs(),TimeUnit.MILLISECONDS);outcome=result.statusCode();return result;
        } catch (InterruptedException exception) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw new PgUnavailableException("PG request interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            response.cancel(true);
            failureCode=exception instanceof TimeoutException || exception.getCause() instanceof java.net.http.HttpTimeoutException ? "timeout" : "transport";
            throw new PgUnavailableException("PG request failed", exception);
        } finally { measurement.finish(outcome,failureCode); }
    }

    private String field(HttpResponse<String> response, String name) {
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode value = root == null ? null : root.get(name);
            return value == null || !value.isString() ? null : value.asText();
        } catch (RuntimeException exception) {
            throw new PgUnavailableException("Invalid PG JSON response", exception);
        }
    }
    private static String path(String key) { return "/payments/" + URLEncoder.encode(key, StandardCharsets.UTF_8); }
    @PreDestroy public void close() { http.shutdownNow(); }
}
