package dev.endnjs.simulator.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public interface HttpTransport extends AutoCloseable {
    enum Target { SERVER, PG, QUEUE }
    record Request(Target target, String method, String path, Map<String, String> headers, Map<String, Object> body, Duration timeout) {
        public Request { headers = Map.copyOf(headers); if (body != null) body = Map.copyOf(body); }
    }
    record Response(int status, Map<String, Object> body) {
        public Response { body = Collections.unmodifiableMap(new LinkedHashMap<>(body)); }
        public boolean ok() { return status >= 200 && status < 300; }
        public String text(String key) {
            if (!(body.get(key) instanceof String value)) throw new IllegalStateException("Missing response field: " + key);
            return value;
        }
        public long number(String key) {
            if (!(body.get(key) instanceof Number value)) throw new IllegalStateException("Missing numeric response field: " + key);
            return value.longValue();
        }
        public boolean flag(String key) {
            if (!(body.get(key) instanceof Boolean value)) throw new IllegalStateException("Missing boolean response field: " + key);
            return value;
        }
        public String code() { return body.get("code") instanceof String value ? value : ""; }
    }
    Response exchange(Request request) throws IOException, InterruptedException;
    @Override default void close() {}
}
