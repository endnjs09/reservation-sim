package dev.endnjs.reservation;

import com.sun.net.httpserver.HttpServer;
import dev.endnjs.reservation.payment.PgClient;
import dev.endnjs.reservation.snapshot.StateSnapshotReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** GET /seats 새로고침 제한(입장키별 최소 간격, 429)과 좌석 조회 캐시 (docs/DECISION_CLAUDE.md). */
@SpringBootTest(properties={"reservation.scheduler.enabled=false","admission.required=true","admission.key-secret=integration-secret"})
@AutoConfigureMockMvc
@Testcontainers
@Import(S1IntegrationTest.TestClockConfig.class)
class SeatsRefreshIntegrationTest {
    private static final Instant NOW=Instant.parse("2026-10-05T00:00:00Z");
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17")
            .withDatabaseName("reservation").withUsername("reservation").withPassword("reservation");
    private static final HttpServer QUEUE=queue();
    private static HttpServer queue() {
        try {
            var queue=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            queue.createContext("/",request -> { request.getRequestBody().readAllBytes();request.sendResponseHeaders(200,-1);request.close(); });
            queue.start();return queue;
        } catch(java.io.IOException failed) { throw new IllegalStateException(failed); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);registry.add("spring.datasource.username",POSTGRES::getUsername);registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("admission.queue-url",()->"http://127.0.0.1:"+QUEUE.getAddress().getPort());
    }
    @AfterAll static void closeQueue() { QUEUE.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PgClient pg;
    @MockitoSpyBean StateSnapshotReader snapshots;
    @Autowired dev.endnjs.reservation.seat.SeatsGate gate;
    @Autowired dev.endnjs.reservation.config.RuntimeConfigStore configs;

    private void reset(Map<String,Object> extra) throws Exception {
        clock.set(NOW);
        var config=new LinkedHashMap<String,Object>(Map.ofEntries(Map.entry("rows",1),Map.entry("cols",4),Map.entry("grades",List.of(Map.of("name","VIP","rows",1,"price",100))),
                Map.entry("strategy","conditional"),Map.entry("dbBackstop",true),Map.entry("timeScale",1),Map.entry("saleDurationSec",1200),
                Map.entry("holdTtlSec",420),Map.entry("runEpoch","current")));
        // reset에 없는 값은 직전 실행 값을 이어받으므로 세 값을 늘 보낸다 (시뮬레이터도 늘 보냄)
        config.putAll(Map.of("seatsRateLimitEnabled",true,"seatsMinIntervalSec",1.0,"seatsCacheSec",0));
        config.putAll(extra);
        assertThat(post("/admin/reset",config,null).getResponse().getStatus()).isEqualTo(200);
        when(pg.confirm(anyString(),any(UUID.class),anyInt())).thenReturn(new PgClient.Approval(true,null));
        clearInvocations(snapshots);
    }
    private String key(String uid) throws Exception {
        String message="v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(Map.of("kid",UUID.randomUUID().toString(),"uid",uid,"run","current",
                "iat",NOW.toEpochMilli(),"exp",NOW.plusSeconds(3600).toEpochMilli())));
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("integration-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return message+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
    private MvcResult post(String path,Object body,String key) throws Exception {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if(key!=null) request.header("X-Admission-Key",key);
        if(path.equals("/holds")) request.header("Idempotency-Key",UUID.randomUUID().toString());
        return mvc.perform(request).andReturn();
    }
    private MvcResult seats(String key) throws Exception { return mvc.perform(get("/seats").header("X-Admission-Key",key)).andReturn(); }
    private JsonNode body(MvcResult result) { return json.readTree(result.getResponse().getContentAsByteArray()); }
    private String seatStatus(MvcResult result,long id) {
        for(var seat:body(result).get("seats")) if(seat.get("id").asLong()==id) return seat.get("status").asString();
        throw new AssertionError("no seat "+id);
    }

    @Test void secondLookupByTheSameKidWithinTheIntervalIs429AndOtherKidsPass() throws Exception {
        reset(Map.of());
        String a=key("a"),b=key("b");
        assertThat(seats(a).getResponse().getStatus()).isEqualTo(200);
        clock.advance(Duration.ofMillis(400));
        var limited=seats(a);
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(limited).get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(body(limited).get("retryAfterMs").asLong()).isEqualTo(600);
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("1");
        // 계약 파일과 같은 필드 (시뮬레이터 가짜 응답이 이 파일에서 만들어진다)
        var contract=json.readTree(java.nio.file.Path.of("../contracts/seats-rate-limited.json").toFile());
        assertThat(body(limited).propertyNames()).containsExactlyInAnyOrderElementsOf(contract.propertyNames());
        assertThat(seats(b).getResponse().getStatus()).isEqualTo(200); // 다른 입장키는 따로 셈
        clock.advance(Duration.ofMillis(600));
        assertThat(seats(a).getResponse().getStatus()).isEqualTo(200); // 1초(시뮬레이션) 지나면 다시 허용
        verify(snapshots,times(3)).seats(); // 429는 DB까지 가지 않는다
    }
    @Test void intervalIsSimulationSecondsAndResetForgetsKids() throws Exception {
        reset(Map.of("timeScale",4,"seatsMinIntervalSec",2.0));
        String a=key("a");
        assertThat(seats(a).getResponse().getStatus()).isEqualTo(200);
        clock.advance(Duration.ofMillis(300));
        var limited=seats(a);
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(limited).get("retryAfterMs").asLong()).isEqualTo(200); // 2초 ÷ 4배속 = 실제 500ms
        clock.advance(Duration.ofMillis(200));
        assertThat(seats(a).getResponse().getStatus()).isEqualTo(200);
        reset(Map.of("timeScale",4,"seatsMinIntervalSec",2.0));
        assertThat(seats(a).getResponse().getStatus()).isEqualTo(200); // reset이 kid 기록을 비움
    }
    @Test void disabledLimitAllowsBackToBackLookups() throws Exception {
        reset(Map.of("seatsRateLimitEnabled",false));
        String a=key("a");
        for(int i=0;i<3;i++) assertThat(seats(a).getResponse().getStatus()).isEqualTo(200);
    }
    @Test void holdsAreNotRateLimited() throws Exception {
        reset(Map.of());
        String a=key("a");
        assertThat(seats(a).getResponse().getStatus()).isEqualTo(200);
        assertThat(seats(a).getResponse().getStatus()).isEqualTo(429);
        var held=post("/holds",Map.of("userId","a","seatIds",List.of(1)),a);
        assertThat(held.getResponse().getStatus()).isEqualTo(201);
        var again=post("/holds",Map.of("userId","a","seatIds",List.of(2)),a);
        assertThat(again.getResponse().getStatus()).isEqualTo(409); // 같은 kid로 곧바로 다시 선점해도 429가 아니라 업무 규칙
        assertThat(body(again).get("code").asString()).isEqualTo("USER_ALREADY_HOLDING");
    }
    @Test void cacheServesEveryoneFromOneDatabaseReadPerInterval() throws Exception {
        reset(Map.of("seatsCacheSec",1));
        for(int i=0;i<5;i++) { assertThat(seats(key("u"+i)).getResponse().getStatus()).isEqualTo(200);clock.advance(Duration.ofMillis(100)); }
        verify(snapshots,times(1)).seats();
        clock.advance(Duration.ofMillis(500));
        assertThat(seats(key("late")).getResponse().getStatus()).isEqualTo(200);
        verify(snapshots,times(2)).seats();
        var cache=gate.metrics(configs.current()); // /admin/metrics는 1초마다 갱신되는 스냅샷이라 카운터를 직접 읽음
        assertThat(cache).containsEntry("hits",4L).containsEntry("misses",2L).containsEntry("dbReads",2L);
    }
    @Test void cacheZeroReadsTheDatabaseEveryTime() throws Exception {
        reset(Map.of());
        for(int i=0;i<3;i++) assertThat(seats(key("u"+i)).getResponse().getStatus()).isEqualTo(200);
        verify(snapshots,times(3)).seats();
    }
    @Test void holdingASeatThatTheCachedMapStillShowsAvailableIs409WithoutDoubleSale() throws Exception {
        reset(Map.of("seatsCacheSec",1));
        String owner=key("owner"),late=key("late");
        assertThat(seatStatus(seats(owner),1)).isEqualTo("AVAILABLE"); // 캐시가 채워짐
        long id=body(post("/holds",Map.of("userId","owner","seatIds",List.of(1)),owner)).get("reservationId").asLong();
        var order=body(post("/holds/"+id+"/checkout",Map.of("userId","owner"),owner));
        assertThat(post("/payments/confirm",Map.of("userId","owner","orderId",order.get("orderId").asString(),"paymentKey","key","amount",100),owner)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=1",String.class)).isEqualTo("SOLD");
        clock.advance(Duration.ofMillis(300));
        assertThat(seatStatus(seats(late),1)).isEqualTo("AVAILABLE"); // 캐시된 화면은 아직 빈 좌석
        var held=post("/holds",Map.of("userId","late","seatIds",List.of(1)),late);
        assertThat(held.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(held).get("code").asString()).isEqualTo("SEAT_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_seats rs JOIN reservations r ON r.id=rs.reservation_id WHERE rs.seat_id=1 AND r.status IN ('HELD','CONFIRMING','CONFIRMED','PENDING_DEPOSIT')",Long.class)).isEqualTo(1);
    }
}
