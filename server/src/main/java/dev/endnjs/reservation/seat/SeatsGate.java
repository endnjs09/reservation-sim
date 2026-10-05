package dev.endnjs.reservation.seat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import dev.endnjs.reservation.config.RuntimeConfig;
import org.springframework.stereotype.Component;

/**
 * GET /seats 앞단 (docs/DECISION_CLAUDE.md): 입장키 검사 → 새로고침 제한 → 캐시 → DB.
 * 새로고침 제한: 입장키(kid)마다 seatsMinIntervalSec(시뮬레이션 초) 안의 재조회는 429. 마지막 허용 시각만 메모리에 두고 reset 때 비운다.
 * 캐시: seatsCacheSec(시뮬레이션 초) 동안 응답 전체를 모든 사용자가 같이 쓴다. 선점·결제·취소는 캐시와 무관하게 DB에서 조건부 처리.
 * 시각은 서버 Clock(AnchoredClock, 테스트에서는 MutableClock)으로 잰다.
 */
@Component
public class SeatsGate {
    public record Limited(long retryAfterMs) {}
    private record Cached(Object response, Instant until, String epoch) {}
    private final Clock clock;
    private final Map<UUID, Instant> lastAllowed = new ConcurrentHashMap<>();
    private volatile Cached cached;
    private final AtomicLong hits = new AtomicLong(), misses = new AtomicLong(), dbReads = new AtomicLong(), rateLimited = new AtomicLong();
    public SeatsGate(Clock clock) { this.clock = clock; }

    /** 허용이면 null. 허용한 조회만 마지막 시각으로 남는다 (429는 기다리는 시간을 늘리지 않는다). */
    public Limited admit(UUID kid, RuntimeConfig config) {
        if (kid == null || !config.seatsRateLimitEnabled()) return null;
        Instant now = clock.instant();
        Duration interval = simulation(config.seatsMinIntervalSec(), config);
        var verdict = new Limited[1];
        lastAllowed.compute(kid, (id, last) -> {
            if (last != null && now.isBefore(last.plus(interval))) {
                verdict[0] = new Limited(Math.max(1, (Duration.between(now, last.plus(interval)).toNanos() + 999_999) / 1_000_000));
                return last;
            }
            return now;
        });
        if (verdict[0] != null) rateLimited.incrementAndGet();
        return verdict[0];
    }

    /** 캐시가 꺼져 있거나 지났으면(또는 다른 실행 runEpoch면) DB에서 읽는다. 동시에 지난 경우 한 요청만 DB로 가고 나머지는 그 결과를 기다린다. */
    @SuppressWarnings("unchecked")
    public <T> T read(RuntimeConfig config, String epoch, Supplier<T> database) {
        if (config.seatsCacheSec() <= 0) { dbReads.incrementAndGet(); return database.get(); }
        var current = cached;
        if (current != null && java.util.Objects.equals(current.epoch(), epoch) && clock.instant().isBefore(current.until())) { hits.incrementAndGet(); return (T) current.response(); }
        synchronized (this) {
            current = cached;
            if (current != null && java.util.Objects.equals(current.epoch(), epoch) && clock.instant().isBefore(current.until())) { hits.incrementAndGet(); return (T) current.response(); }
            misses.incrementAndGet(); dbReads.incrementAndGet();
            T response = database.get();
            cached = new Cached(response, clock.instant().plus(simulation(config.seatsCacheSec(), config)), epoch);
            return response;
        }
    }

    /** 누적 값 (reset 이후). dbReads = GET /seats가 실제로 DB를 읽은 수. */
    public Map<String, Object> metrics(RuntimeConfig config) {
        var result = new LinkedHashMap<String, Object>();
        result.put("cacheSec", config.seatsCacheSec()); result.put("hits", hits.get()); result.put("misses", misses.get());
        result.put("dbReads", dbReads.get()); result.put("rateLimited", rateLimited.get());
        result.put("rateLimitEnabled", config.seatsRateLimitEnabled()); result.put("minIntervalSec", config.seatsMinIntervalSec());
        return result;
    }

    public synchronized void reset() {
        lastAllowed.clear(); cached = null;
        hits.set(0); misses.set(0); dbReads.set(0); rateLimited.set(0);
    }

    private static Duration simulation(double seconds, RuntimeConfig config) {
        return Duration.ofNanos(Math.round(seconds * 1_000_000_000L / config.timeScale()));
    }
}
