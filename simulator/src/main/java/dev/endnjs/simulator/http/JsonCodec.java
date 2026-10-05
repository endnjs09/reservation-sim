package dev.endnjs.simulator.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import dev.endnjs.simulator.engine.RunConfig;
import dev.endnjs.simulator.engine.Summary;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

public final class JsonCodec {
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    public RunConfig readConfig(Path path) throws IOException { return config(Files.readString(path)); }
    @SuppressWarnings("unchecked")
    public RunConfig config(String text) {
        var node = json.readTree(text);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Config must be a JSON object");
        Map<String, Object> defaults = json.convertValue(RunConfig.defaults(), Map.class);
        Map<String, Object> supplied = json.convertValue(node, Map.class);
        supplied.remove("earlyQuitRate"); // 10.3: removed setting; older presets and records may still carry it
        var merged=merge(defaults,supplied);
        if(!supplied.containsKey("busyMaxExtraSec")) merged.put("busyMaxExtraSec",((Number)merged.get("holdTtlSec")).intValue()+((Number)merged.get("confirmDeadlineSec")).intValue()+60);
        return json.convertValue(merged, RunConfig.class);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> supplied) {
        var result = new LinkedHashMap<>(base);
        supplied.forEach((key, value) -> {
            Object previous = result.get(key);
            result.put(key, previous instanceof Map<?, ?> && value instanceof Map<?, ?>
                    ? merge((Map<String, Object>) previous, (Map<String, Object>) value) : value);
        });
        return result;
    }
    public Object decode(String text) { return json.readValue(text,Object.class); }
    public String encode(Object value) { return json.writeValueAsString(value); }
    @SuppressWarnings("unchecked")
    public Summary readSummary(Path path) throws IOException {
        // Historical output remains readable after the explicitly removed runtime setting.
        Map<String,Object> saved=json.readValue(Files.readString(path),Map.class);
        if(saved.get("config") instanceof Map<?,?> config) {
            var old=(Map<String,Object>)config;old.remove("standbyLimit");old.remove("earlyQuitRate");old.putIfAbsent("queueMode","EMBEDDED");
            old.putIfAbsent("closeQueueOnSoldOut",false);
        }
        return json.convertValue(saved,Summary.class);
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> response(String value) {
        try {
            var node = json.readTree(value);
            return node != null && node.isObject() ? json.convertValue(node, Map.class) : Map.of();
        } catch (RuntimeException malformed) { return Map.of(); }
    }
    public void writeSummary(Path path, Summary summary) throws IOException {
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Files.writeString(absolute, json.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n");
    }
}
