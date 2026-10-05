package dev.endnjs.reservation;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3장 시계 기준점 (실제 서버, 실제 Clock 빈). anchorAt을 1시간 전 값으로 보내면 서버 시각이 anchorAt + 경과를 따라야 한다.
 * 시스템 시계를 직접 쓰는 코드가 남아 있으면 1시간 차이로 드러난다.
 */
@SpringBootTest(properties = {"reservation.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class W3AnchorIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("reservation").withUsername("reservation").withPassword("reservation");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("internal.notifications-enabled", () -> false);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("admission.required", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String post(String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/admin/reset").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
    }
    private Instant serverTime() throws Exception {
        var stats = json.readTree(mvc.perform(MockMvcRequestBuilders.get("/admin/stats")).andReturn().getResponse().getContentAsString());
        return Instant.parse(stats.get("serverTime").asText());
    }
    private Instant saleEndAt() throws Exception {
        var seats = json.readTree(mvc.perform(MockMvcRequestBuilders.get("/seats")).andReturn().getResponse().getContentAsString());
        return Instant.parse(seats.get("saleEndAt").asText());
    }

    @Test void serverTimeFollowsTheAnchorNotTheSystemClock() throws Exception {
        serverTime(); post("{\"timeScale\":1}"); // 첫 요청(조회·reset)의 초기화 시간을 측정에서 뺀다
        Instant anchorAt = Instant.now().minus(Duration.ofHours(1));
        long sent = System.nanoTime();
        post("{\"anchorAt\":\"" + anchorAt + "\",\"timeScale\":1,\"saleDurationSec\":1200}");
        Thread.sleep(300);
        Instant expected = anchorAt.plusNanos(System.nanoTime() - sent);
        assertThat(Duration.between(expected, serverTime()).abs()).isLessThan(Duration.ofMillis(100));
    }
    @Test void omittedSaleEndAtIsAnchorPlusSaleDurationOverTimeScale() throws Exception {
        Instant anchorAt = Instant.now().minus(Duration.ofHours(1));
        post("{\"anchorAt\":\"" + anchorAt + "\",\"timeScale\":4,\"saleDurationSec\":1200}");
        assertThat(Duration.between(anchorAt.plusSeconds(300), saleEndAt()).abs()).isLessThan(Duration.ofMillis(100));
    }
    @Test void resetWithoutAnchorKeepsWorking() throws Exception {
        post("{\"timeScale\":1,\"saleDurationSec\":1200}");
        assertThat(serverTime()).isNotNull();
    }
}
