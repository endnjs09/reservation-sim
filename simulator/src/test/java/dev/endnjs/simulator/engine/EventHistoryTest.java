package dev.endnjs.simulator.engine;

import java.util.Collections;
import dev.endnjs.simulator.http.JsonCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 실시간 이벤트 목록: 1초 창(recentEvents)과 따로 마지막 80건을 보관해, 실행이 끝나거나 화면을 새로 열어도 목록이 남는다. */
class EventHistoryTest {
    @Test void keepsTheLastEightyEventsAfterTheOneSecondWindowPasses() {
        var config=new JsonCodec().config("{\"users\":1,\"timeScale\":1}");
        var time=new V5EngineTest.Time();var stats=new RunStats(config,time,Collections.nCopies(1,Persona.FAST));
        for(int i=1;i<=100;i++) stats.note("u-0001","사건 "+i);
        time.sleep(5000);
        var live=stats.sampleLive(false);
        assertThat(live.recentEvents()).isEmpty();
        assertThat(live.eventHistory()).hasSize(80);
        assertThat(live.eventHistory().getFirst()).endsWith("사건 21");assertThat(live.eventHistory().getLast()).endsWith("사건 100"); // 오래된 것 → 최신
    }
}
