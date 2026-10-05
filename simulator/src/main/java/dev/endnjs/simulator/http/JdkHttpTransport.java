package dev.endnjs.simulator.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import dev.endnjs.simulator.engine.HttpTransport;
import dev.endnjs.simulator.engine.RunConfig;

public final class JdkHttpTransport implements HttpTransport {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
            .version(HttpClient.Version.HTTP_1_1).build();
    private final RunConfig.Targets targets;
    private final JsonCodec json;
    public JdkHttpTransport(RunConfig.Targets targets, JsonCodec json) { this.targets = targets; this.json = json; }
    @Override
    public Response exchange(Request request) throws IOException, InterruptedException {
        String base = (switch(request.target()) { case SERVER -> targets.server(); case PG -> targets.pg(); case QUEUE -> targets.queue(); }).replaceAll("/+$", "");
        // HttpRequest.timeout은 쓰지 않는다: JDK 21 HttpClient는 끝난 요청의 응답 타이머가 남아 같은 연결을 재사용한
        // 다음 요청을 끊는 경우가 있다 (W3StaleTimerTest, docs/DECISION_CLAUDE.md). 시간 제한은 아래 get(timeout)이 전부 맡는다.
        var builder = HttpRequest.newBuilder(URI.create(base + request.path()));
        request.headers().forEach(builder::header);
        if (request.body() == null) builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(request.method(),
                HttpRequest.BodyPublishers.ofString(json.encode(request.body()), StandardCharsets.UTF_8));
        var pending = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            var response = pending.get(request.timeout().toNanos(), TimeUnit.NANOSECONDS);
            Object decoded;try { decoded=json.decode(response.body()); } catch(RuntimeException malformed) { decoded=null; }
            return new Response(response.statusCode(),decoded instanceof java.util.List<?> list ? java.util.Map.of("$items",list) : json.response(response.body()));
        } catch (InterruptedException interrupted) {
            pending.cancel(true); throw interrupted;
        } catch (TimeoutException | ExecutionException failure) {
            pending.cancel(true); throw new IOException("HTTP exchange failed", failure);
        }
    }
    @Override public void close() { http.shutdownNow(); }
}
