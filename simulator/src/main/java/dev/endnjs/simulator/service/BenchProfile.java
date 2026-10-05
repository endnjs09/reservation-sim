package dev.endnjs.simulator.service;

import dev.endnjs.simulator.http.JsonCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 10.4 측정 환경: scripts/bench-up.sh가 쓴 logs/bench.json을 -Dbench.profile로 받아 run.json environment.bench에 기록한다.
 * 프로필 값만 믿지 않고 실제 힙 최대치와 대조해 verified·mismatch를 남긴다. bench 밖 실행이면 null.
 */
final class BenchProfile {
    private static final Pattern XMX = Pattern.compile("-Xmx(\\d+)([gGmM])");

    private BenchProfile() {}

    static Map<String,Object> read() { return read(System.getProperty("bench.profile")); }

    static Map<String,Object> read(String path) {
        if (path == null || path.isBlank()) return null;
        try { return RunStore.object(new JsonCodec().decode(Files.readString(Path.of(path)))); }
        catch (Exception unreadable) {
            var result = new LinkedHashMap<String,Object>();
            result.put("error", "bench 프로필을 읽지 못함: " + path);
            return result;
        }
    }

    /** heap 설정의 -Xmx를 MB로. 없으면 null. */
    static Long xmxMb(Object heap) {
        if (!(heap instanceof String text)) return null;
        var match = XMX.matcher(text);
        if (!match.find()) return null;
        long value = Long.parseLong(match.group(1));
        return match.group(2).equalsIgnoreCase("g") ? value * 1024 : value;
    }

    /** 실제 값(서버가 보고한 힙 최대, 시뮬레이터 자신의 힙 최대)과 대조한 프로필 사본. */
    static Map<String,Object> verify(Map<String,Object> profile, Double serverHeapMaxMb, long simulatorMaxBytes) {
        if (profile == null) return null;
        var result = new LinkedHashMap<>(profile);
        var heap = RunStore.object(profile.get("heap"));
        var verified = new LinkedHashMap<String,Object>();
        var mismatch = new ArrayList<String>();
        long simulatorMb = simulatorMaxBytes / (1024 * 1024);
        verified.put("serverHeapMaxMb", serverHeapMaxMb);
        verified.put("simulatorHeapMaxMb", simulatorMb);
        check("server", xmxMb(heap.get("server")), serverHeapMaxMb, mismatch);
        check("simulator", xmxMb(heap.get("simulator")), (double) simulatorMb, mismatch);
        result.put("verified", verified);
        result.put("mismatch", mismatch);
        return result;
    }

    /** JVM이 보고하는 최대 힙은 -Xmx보다 조금 작을 수 있어 5% 안이면 같다고 본다. */
    private static void check(String name, Long expectedMb, Double actualMb, java.util.List<String> mismatch) {
        if (expectedMb == null) return;
        if (actualMb == null || Math.abs(actualMb - expectedMb) > expectedMb * 0.05) mismatch.add(name);
    }
}
