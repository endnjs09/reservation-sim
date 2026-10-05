package dev.endnjs.reservation;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import dev.endnjs.reservation.config.AnchoredClock;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 3장 시계 기준점: 가짜 단조 시계 + 6% 빠른 가짜 벽시계. */
class AnchoredClockTest {
    private static final Instant WALL_START = Instant.parse("2026-10-04T00:00:00Z");
    private final AtomicLong nanos = new AtomicLong(5_000_000_000L);
    /** 단조 시계 1초마다 1.06초 가는 벽시계. */
    private final MutableClock wall = new MutableClock(WALL_START);

    private void advance(Duration real) {
        nanos.addAndGet(real.toNanos());
        wall.set(wall.instant().plusNanos(Math.round(real.toNanos() * 1.06)));
    }

    @Test void beforeResetItIsTheSystemClock() {
        var clock = new AnchoredClock(wall, nanos::get);
        advance(Duration.ofSeconds(100));
        assertThat(clock.instant()).isEqualTo(wall.instant());
        assertThat(clock.anchorAt()).isNull();
    }
    @Test void afterResetItFollowsTheAnchorPlusMonotonicElapsedNotTheFastWallClock() {
        var clock = new AnchoredClock(wall, nanos::get);
        var anchorAt = Instant.parse("2026-10-04T01:00:00Z");
        clock.anchor(anchorAt, nanos.get());
        advance(Duration.ofSeconds(1200));
        assertThat(clock.instant()).isEqualTo(anchorAt.plusSeconds(1200));
        assertThat(Duration.between(WALL_START, wall.instant()).toSeconds()).isEqualTo(1272); // 벽시계는 72초 앞서 감
    }
    @Test void anchorUsesTheReceivedMomentSoProcessingTimeIsNotLost() {
        var clock = new AnchoredClock(wall, nanos::get);
        long received = nanos.get();
        advance(Duration.ofMillis(800)); // reset 처리(TRUNCATE 등)
        clock.anchor(Instant.parse("2026-10-04T01:00:00Z"), received);
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-04T01:00:00.800Z"));
    }
    @Test void secondResetReplacesTheAnchor() {
        var clock = new AnchoredClock(wall, nanos::get);
        clock.anchor(Instant.parse("2026-10-04T01:00:00Z"), nanos.get());
        advance(Duration.ofSeconds(30));
        clock.anchor(Instant.parse("2026-10-04T05:00:00Z"), nanos.get());
        advance(Duration.ofSeconds(2));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-04T05:00:02Z"));
        assertThat(clock.millis()).isEqualTo(Instant.parse("2026-10-04T05:00:02Z").toEpochMilli());
    }
}
