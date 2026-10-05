package dev.endnjs.simulator.service;

import dev.endnjs.simulator.engine.*;
import dev.endnjs.simulator.http.JsonCodec;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 좌석 조회 새로고침 제한(429 RATE_LIMITED)은 에러율에 넣지 않고 따로 센다. */
class RateLimitedMeasurementsTest {
    private RunMeasurements.Frame sample(Map<String,Object> seatsStatus,Map<String,Object> seatsCache) {
        var config=new JsonCodec().config("{\"users\":1,\"timeScale\":1}");
        var stats=new RunStats(config,RunTime.system(),List.of(Persona.FAST));
        var server=new LinkedHashMap<String,Object>(Map.of("phase","RUSH",
                "endpoints",Map.of("seats",Map.of("rps",100,"status",seatsStatus,"latency",Map.of("p95",3)),
                        "holds",Map.of("rps",10,"status",Map.of("2xx",8L,"409",2L),"latency",Map.of("p95",5)))));
        server.put("seatsCache",seatsCache);
        return new RunMeasurements(config).sample(1000,server,Map.of(),Map.of(),stats.sampleLive(false),stats.clientMetrics());
    }
    @Test void rateLimitedResponsesAreNeitherErrorsNorPartOfTheErrorRate() {
        var frame=sample(Map.of("2xx",10L,"429",90L),Map.of("rateLimitedRps",90.0,"dbReadRps",10.0,"hitRps",0.0));
        // 429를 빼면 seats 10건 + holds 10건 중 409 2건 = 10%. 429를 에러로 세면 92/110 ≈ 83.6%, 분모에만 넣으면 2/110 ≈ 1.8%
        assertThat(frame.signals().get("errPct")).isEqualTo(10.0);
        assertThat(frame.signals().get("rateLimitedRps")).isEqualTo(90.0);
        var seriesSeatsCache=(Map<?,?>)((Map<?,?>)frame.series().get("server")).get("seatsCache");
        assertThat(seriesSeatsCache.get("rateLimitedRps")).isEqualTo(90.0);
    }
    @Test void classifiesRateLimitedSeparatelyFromShed() {
        assertThat(RequestHistograms.classify(429,"RATE_LIMITED")).isEqualTo("rateLimited");
        assertThat(RequestHistograms.classify(429,null)).isEqualTo("shed");
        assertThat(RequestHistograms.zeros()).containsKey("rateLimited");
    }
}
