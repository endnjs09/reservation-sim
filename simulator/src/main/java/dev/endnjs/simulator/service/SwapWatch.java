package dev.endnjs.simulator.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** 10.4: 실행 중 스왑 사용이 100MB를 넘으면 SWAP_USED 사건을 한 번 남긴다 (ENV_DEGRADED 경고 근거). */
final class SwapWatch {
    static final long LIMIT_MB = 100;
    private final Supplier<HostMemory> memory;
    private boolean reported;

    SwapWatch(Supplier<HostMemory> memory) { this.memory = memory; }

    /** 넘었으면 사건, 아니면(또는 이미 남겼거나 읽을 수 없으면) null. */
    synchronized Map<String,Object> check(long t) {
        if (reported) return null;
        Long used = memory.get().swapUsedMb();
        if (used == null || used <= LIMIT_MB) return null;
        reported = true;
        var event = new LinkedHashMap<String,Object>();
        event.put("t", t); event.put("type", "SWAP_USED"); event.put("swapUsedMb", used);
        return event;
    }
}
