package dev.endnjs.simulator.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import dev.endnjs.simulator.http.JsonCodec;
import org.junit.jupiter.api.Test;
import static dev.endnjs.simulator.engine.HttpTransport.Target.*;
import static org.assertj.core.api.Assertions.*;

/** 3장 시계 기준점 (시뮬레이터): 벽시계가 단조 시계보다 6% 빠른 환경. */
class W3AnchorEngineTest {
    private static final Instant WALL_START=Instant.parse("2026-10-04T09:00:00Z");

    /** 벽시계가 단조 시계 1초마다 1.06초 가는 시간. */
    static final class SkewedTime implements RunTime {
        final AtomicLong nanos=new AtomicLong(1_000_000_000L);
        /** true면 시계를 읽을 때마다 1ms가 흐른다: 기준점을 두 번 읽는 코드는 다른 값을 얻는다. */
        volatile boolean ticking;
        public Instant instant() { if(ticking) nanos.addAndGet(1_000_000L);return WALL_START.plusNanos(Math.round(nanos.get()*1.06)); }
        public long nanoTime() { return ticking ? nanos.addAndGet(1_000_000L) : nanos.get(); }
        public void sleep(long millis) { nanos.addAndGet(millis*1_000_000L); }
    }
    /** 대기열이 판매 종료로 닫혀 있어 사용자가 바로 끝나는 서버들. reset 본문을 남긴다. */
    static final class ClosedTargets implements HttpTransport {
        final SkewedTime time=new SkewedTime();final List<Request> calls=Collections.synchronizedList(new ArrayList<>());
        public Response exchange(Request request) {
            calls.add(request);time.nanos.addAndGet(1_000_000L);
            if(request.path().equals("/queue/enter")) return new Response(200,Map.of("token",UUID.randomUUID().toString(),"status","CLOSED","reason","SALE_ENDED"));
            return new Response(200,Map.of());
        }
        public void close() {}
        Map<String,Object> reset(Target target) {
            return calls.stream().filter(c -> c.target()==target && c.path().equals("/admin/reset")).findFirst().orElseThrow().body();
        }
    }

    @Test void eachServerGetsTheAnchorClockAtSendTimeAndSaleEndStaysOnTheAnchor() {
        var targets=new ClosedTargets();targets.time.ticking=true;
        var config=new JsonCodec().config("{\"users\":1,\"timeScale\":4,\"saleDurationSec\":1200,\"timeLimitSec\":60}");
        var engine=new RunEngine(config,targets,targets.time);
        targets.time.sleep(3000); // 엔진 생성과 실행 사이의 시간: 기준점은 run()에서 잡혀야 한다
        engine.run();
        var anchor=engine.anchor();
        assertThat(anchor).isNotNull();
        // 서버별 anchorAt = 기준 시각 + 보내기 직전까지의 단조 경과: 보낸 순서(queue → pg → server)대로 늘고 기준 시각에서 크게 벗어나지 않는다
        Instant q=(Instant)targets.reset(QUEUE).get("anchorAt"),p=(Instant)targets.reset(PG).get("anchorAt"),sv=(Instant)targets.reset(SERVER).get("anchorAt");
        assertThat(q).isAfterOrEqualTo(anchor.at());assertThat(p).isAfter(q);assertThat(sv).isAfter(p);
        assertThat(Duration.between(anchor.at(),sv)).isLessThan(Duration.ofSeconds(1));
        Instant saleEnd=anchor.at().plus(Duration.ofSeconds(300));
        assertThat(targets.reset(SERVER)).containsEntry("saleEndAt",saleEnd);
        assertThat(targets.reset(QUEUE)).containsEntry("saleEndAt",saleEnd);
        assertThat(engine.saleEndAt()).isEqualTo(saleEnd);
        assertThat(engine.stats().startedAt()).isEqualTo(anchor.at());
    }
    @Test void afterTheAnchorTheEngineClockIgnoresTheFastWallClock() {
        var base=new SkewedTime();var time=new AnchorTime(base);
        var anchor=time.anchor();
        base.sleep(1_150_000); // 단조 시계로 1150초
        assertThat(time.instant()).isEqualTo(anchor.at().plusSeconds(1150));
        assertThat(base.instant()).isAfter(anchor.at().plusSeconds(1200)); // 벽시계로는 이미 판매 종료(1200초) 이후
        assertThat(time.anchor()).isSameAs(anchor);
    }
    @Test void beforeTheAnchorItIsTheBaseClock() {
        var base=new SkewedTime();var time=new AnchorTime(base);
        base.sleep(5000);
        assertThat(time.instant()).isEqualTo(base.instant());assertThat(time.current()).isNull();
    }
}
