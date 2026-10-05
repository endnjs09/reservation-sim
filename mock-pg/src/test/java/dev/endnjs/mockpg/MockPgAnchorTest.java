package dev.endnjs.mockpg;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 3장 시계 기준점: reset 본문의 anchorAt으로 mock-pg Clock 기준을 잡는다 (본문은 선택). */
class MockPgAnchorTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final AnchoredClock clock = new AnchoredClock();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MockPgController(new MockPgService(clock, millis -> {}), clock)).build();

    private Instant serverTime() throws Exception {
        return Instant.parse(json.readTree(mvc.perform(get("/admin/stats")).andReturn().getResponse().getContentAsString()).get("serverTime").asString());
    }

    @Test void resetWithAnchorAtMovesTheClock() throws Exception {
        serverTime(); mvc.perform(post("/admin/reset")); // 첫 요청(조회·reset)의 초기화 시간을 측정에서 뺀다
        Instant anchorAt = Instant.now().minus(Duration.ofHours(1));
        long sent = System.nanoTime();
        var status = mvc.perform(post("/admin/reset").contentType(MediaType.APPLICATION_JSON).content("{\"anchorAt\":\"" + anchorAt + "\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        Thread.sleep(200);
        Instant expected = anchorAt.plusNanos(System.nanoTime() - sent);
        assertThat(Duration.between(expected, serverTime()).abs()).isLessThan(Duration.ofMillis(100));
    }
    @Test void resetWithoutBodyStillWorksAndKeepsTheSystemClock() throws Exception {
        assertThat(mvc.perform(post("/admin/reset")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(Duration.between(Instant.now(), serverTime()).abs()).isLessThan(Duration.ofSeconds(1));
    }
}
