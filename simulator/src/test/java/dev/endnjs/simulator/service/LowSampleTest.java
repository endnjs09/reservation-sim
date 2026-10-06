package dev.endnjs.simulator.service;

import dev.endnjs.simulator.engine.*;
import dev.endnjs.simulator.http.JsonCodec;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 1초 창의 예약 서버 요청이 minSamplesPerWindow(기본 20) 미만이면 p95·에러율 판정에서 뺀다 (표본 적음). */
class LowSampleTest {
    private final JsonCodec json=new JsonCodec();
    /** n건 중 409 errors건, p95 p95ms인 1초 창. */
    private static Map<String,Object> window(long n,long errors,double p95) {
        return Map.of("phase","RESALE","total",Map.of("rps",n,"latency",Map.of("p50",p95/2,"p95",p95,"p99",p95)),
                "endpoints",Map.of("seats",Map.of("rps",n,"status",errors>0 ? Map.of("2xx",n-errors,"409",errors) : Map.of("2xx",n))));
    }
    private RunMeasurements.Frame sample(RunMeasurements m,RunStats stats,long ms,Map<String,Object> server) {
        return m.sample(ms,server,Map.of(),Map.of(),stats.sampleLive(false),stats.clientMetrics());
    }
    @Test void nineteenRequestWindowIsLowSampleAndTwentyIsCounted() {
        var config=json.config("{\"users\":1,\"timeScale\":1}");var stats=new RunStats(config,RunTime.system(),List.of(Persona.FAST));
        var m=new RunMeasurements(config);
        var low=sample(m,stats,1000,window(19,10,389.9));
        assertThat(low.signals()).containsEntry("lowSample",true).containsEntry("samples",19L);
        assertThat(RunStore.object(low.signals().get("levels"))).containsEntry("p95","low").containsEntry("err","low");
        assertThat(low.events()).noneSatisfy(e -> assertThat(e.get("type")).isEqualTo("SLO_BREACH_START"));
        var full=sample(m,stats,2000,window(20,10,310));
        assertThat(full.signals()).containsEntry("lowSample",false);
        assertThat(RunStore.object(full.signals().get("levels"))).containsEntry("p95","bad").containsEntry("err","bad");
        var summary=m.finish(stats.summary(),Map.of(),stats.clientMetrics()).signals();
        assertThat(summary).containsEntry("sloBreachSec",1L).containsEntry("p95Max",310.0); // 19건 창의 389.9ms는 빠짐
    }
    /** run-203fb855 t=1004~1005: 초당 3건 중 카드 승인 1건 때문에 p95 389.9ms → SLO 초과로 세지 않는다. */
    @Test void threeRequestSpikeIsNotAnSloBreach() {
        var config=json.config("{\"users\":1,\"timeScale\":1}");var stats=new RunStats(config,RunTime.system(),List.of(Persona.FAST));
        var m=new RunMeasurements(config);
        sample(m,stats,1000,window(3,0,389.9));sample(m,stats,2000,window(3,0,389.9));
        var summary=m.finish(stats.summary(),Map.of(),stats.clientMetrics()).signals();
        assertThat(summary).containsEntry("sloBreachSec",0L);assertThat(summary.get("p95Max")).isNull();
    }
    @Test void storedThresholdsWithoutTheFieldExcludeNothing() {
        var config=json.config("{\"users\":1,\"timeScale\":1,\"thresholds\":{\"p95WarnMs\":100,\"p95SloMs\":300,\"errWarnPct\":8,\"errBadPct\":25,\"poolWarnPct\":70,\"poolBadPct\":90,\"minSamplesPerWindow\":0}}");
        var stats=new RunStats(config,RunTime.system(),List.of(Persona.FAST));var m=new RunMeasurements(config);
        sample(m,stats,1000,window(3,0,389.9));
        assertThat(m.finish(stats.summary(),Map.of(),stats.clientMetrics()).signals()).containsEntry("sloBreachSec",1L);
        assertThat(RunConfig.defaults().thresholds().minSamplesPerWindow()).isEqualTo(20);
        assertThat(new RunConfig.Thresholds(100,300,8,25,70,90,null).minSamplesPerWindow()).isZero(); // 예전 기록
    }
}
