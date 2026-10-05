package dev.endnjs.queue;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class QueueHttpTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private final AnchoredClock clock=new AnchoredClock();
    private final QueueState state=new QueueState(clock,"test-secret",200,20,420,510,false);
    private final QueueMetrics metrics=new QueueMetrics(state);
    private final MockMvc mvc=MockMvcBuilders.standaloneSetup(new QueueController(state,metrics,"internal-test",clock))
            .setControllerAdvice(new QueueController.Errors()).addFilters(new QueueMetricsFilter(metrics)).build();
    private MvcResult send(String path,Object body,String secret) throws Exception {
        var request=post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if(secret!=null) request.header("X-Internal-Secret",secret);return mvc.perform(request).andReturn();
    }
    /** 3장: anchorAt을 1시간 전 값으로 보내면 대기열 서버 시각(serverTime·saleEndAt 판단)이 anchorAt + 경과를 따른다. */
    @Test void resetAnchorMovesTheQueueClockAndStatsShowServerTime() throws Exception {
        // 첫 요청(조회·reset 본문 읽기)의 초기화 시간을 측정에서 뺀다
        mvc.perform(get("/admin/stats"));send("/admin/reset",new QueueConfig(20,3,420,510,1,Instant.now().plusSeconds(1200),false,"warm",null),null);
        Instant anchorAt=Instant.now().minus(Duration.ofHours(1));long sent=System.nanoTime();
        var body=new LinkedHashMap<String,Object>(Map.of("maxActive",20,"admitPerSec",3,"admissionTtlSec",420,"busyMaxExtraSec",510,"timeScale",1,
                "saleEndAt",anchorAt.plusSeconds(1200).toString(),"closeQueueOnSoldOut",false,"runEpoch","run"));body.put("anchorAt",anchorAt.toString());
        assertThat(send("/admin/reset",body,null).getResponse().getStatus()).isEqualTo(200);
        Thread.sleep(200);Instant expected=anchorAt.plusNanos(System.nanoTime()-sent);
        var stats=json.readTree(mvc.perform(get("/admin/stats")).andReturn().getResponse().getContentAsString());
        assertThat(Duration.between(expected,Instant.parse(stats.get("serverTime").asString())).abs()).isLessThan(Duration.ofMillis(100));
        // 판매 종료(anchorAt+1200초)는 아직 오지 않았다: 시스템 시계 기준이면 1시간 전에 끝난 판매로 CLOSED가 된다.
        var enter=json.readTree(send("/queue/enter",Map.of("userId","anchor-user"),null).getResponse().getContentAsString());
        assertThat(enter.get("status").asString()).isEqualTo("WAITING");
    }
    @Test void queueResponsesHideInventoryAndUnknownTokensAre400() throws Exception {
        var result=send("/queue/enter",Map.of("userId","owner"),null);assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var value=json.readTree(result.getResponse().getContentAsString());
        assertThat(value.properties().stream().map(Map.Entry::getKey)).containsExactlyInAnyOrder("token","status","position","pollAfterMs","reason");
        var status=mvc.perform(get("/queue/status").param("token",value.get("token").asString())).andReturn();assertThat(status.getResponse().getStatus()).isEqualTo(200);
        var fields=json.readTree(status.getResponse().getContentAsString()).properties().stream().map(Map.Entry::getKey).toList();
        assertThat(fields).containsExactlyInAnyOrder("status","position","pollAfterMs");
        assertThat(mvc.perform(get("/queue/status").param("token",UUID.randomUUID().toString())).andReturn().getResponse().getStatus()).isEqualTo(400);
    }
    @Test void internalSecretIsRequiredAndUnknownKidIsIdempotent200() throws Exception {
        String path="/internal/slots/"+UUID.randomUUID()+"/events";var event=Map.of("type","HOLD_ACTIVE","at",Instant.now().toString());
        assertThat(send(path,event,null).getResponse().getStatus()).isEqualTo(401);
        assertThat(send(path,event,"wrong").getResponse().getStatus()).isEqualTo(401);
        var valid=send(path,event,"internal-test");assertThat(valid.getResponse().getStatus()).isEqualTo(200);assertThat(json.readTree(valid.getResponse().getContentAsString()).get("ignored").asBoolean()).isTrue();
    }
    @Test void resetAndMetricsExposeBusyCapEvidenceAndResetHistograms() throws Exception {
        var reset=send("/admin/reset",new QueueConfig(20,3,420,510,1,Instant.now().plusSeconds(1200),false,"run",null),null);
        assertThat(reset.getResponse().getStatus()).isEqualTo(200);send("/queue/enter",Map.of("userId","owner"),null);metrics.sample();
        assertThat(((RequestHistograms.Endpoint)((Map<?,?>)metrics.snapshot().get("endpoints")).get("queue.enter")).status()).containsEntry("2xx",1L);
        assertThat(metrics.snapshot()).containsEntry("expiredByBusyCap",0L);assertThat(state.snapshot()).containsKey("busyCapExpirations");
        send("/admin/reset",new QueueConfig(20,3,420,510,1,Instant.now().plusSeconds(1200),false,"next",null),null);
        assertThat(state.stats()).containsEntry("WAITING",0L);assertThat(state.snapshot().get("busyCapExpirations")).isEqualTo(List.of());
    }
}
