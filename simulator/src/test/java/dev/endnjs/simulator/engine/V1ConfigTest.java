package dev.endnjs.simulator.engine;

import dev.endnjs.simulator.http.JsonCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class V1ConfigTest {
    private final JsonCodec codec = new JsonCodec();
    @TempDir Path temp;

    @Test void newDefaultsAndOldPartialPresetsReceiveSaleSettings() {
        var config = codec.config("{}");
        assertThat(config.timeScale()).isEqualTo(4);
        assertThat(config.saleDurationSec()).isEqualTo(1200);
        assertThat(config.holdTtlSec()).isEqualTo(420);
        assertThat(config.admissionTtlSec()).isEqualTo(420);
        var oldPreset = codec.config("{\"holdTtlSec\":180,\"admissionTtlSec\":180,\"targets\":{\"server\":\"http://localhost:18080\"}}");
        assertThat(oldPreset.holdTtlSec()).isEqualTo(180);
        assertThat(oldPreset.admissionTtlSec()).isEqualTo(180);
        assertThat(oldPreset.timeScale()).isEqualTo(4);
        assertThat(oldPreset.targets().pg()).isEqualTo("http://localhost:8081");
    }

    @Test void accelerationValidationPreservesUnscaledNetworkAndPgSettings() {
        for (int scale : List.of(1, 2, 4)) {
            var config = codec.config(codec.encode(Map.of("timeScale", scale, "saleDurationSec", 90)));
            assertThat(config.confirmMinMs()).isEqualTo(100);
            assertThat(config.confirmMaxMs()).isEqualTo(500);
            assertThat(config.confirmDeadlineSec()).isEqualTo(30);
        }
        for (String invalid : List.of("{\"timeScale\":0}", "{\"timeScale\":3}", "{\"saleDurationSec\":0}", "{\"saleDurationSec\":-1}"))
            assertThatThrownBy(() -> codec.config(invalid)).isInstanceOf(RuntimeException.class);
    }

    @SuppressWarnings("unchecked")
    @Test void oldSavedSummaryRemainsReadableWithItsOriginalOneTimesTiming() throws Exception {
        var oldConfig = new LinkedHashMap<>(codec.response(codec.encode(RunConfig.defaults())));
        oldConfig.remove("timeScale"); oldConfig.remove("saleDurationSec");
        oldConfig.put("holdTtlSec", 180); oldConfig.put("admissionTtlSec", 180);
        Path path = temp.resolve("old-summary.json");
        Files.writeString(path, codec.encode(Map.ofEntries(
                Map.entry("startedAt", Instant.parse("2026-10-02T00:00:00Z")), Map.entry("durationMs", 1000),
                Map.entry("config", oldConfig), Map.entry("requests", Map.of("sent", 9, "byEndpoint", Map.of())),
                Map.entry("responses", Map.of()), Map.entry("avgLatencyMs", Map.of()),
                Map.entry("outcomes", Map.of("confirmed", 1, "incomplete", 0)),
                Map.entry("outcomesByPersona", Map.of()), Map.entry("events", Map.of()))));
        var summary = codec.readSummary(path);
        assertThat(summary.config().timeScale()).isEqualTo(1);
        assertThat(summary.config().holdTtlSec()).isEqualTo(180);
        assertThat(summary.durationMs()).isEqualTo(1000);
        assertThat(summary.outcomes().get("confirmed")).isEqualTo(1L);
        assertThat(summary.exitCode()).isZero();
    }
}
