package dev.endnjs.simulator.service;

import dev.endnjs.simulator.engine.*;
import dev.endnjs.simulator.http.JsonCodec;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class W3QueueMeasurementsTest {
    @Test void busyOverTtlIsOnlyAReferenceAndCapDeltaCreatesExactlyOneEvent() {
        var config=new JsonCodec().config("{\"users\":1,\"timeScale\":1}");
        var stats=new RunStats(config,RunTime.system(),List.of(Persona.FAST));
        var measurements=new RunMeasurements(config);var server=Map.<String,Object>of("phase","RUSH");
        var queue=new LinkedHashMap<String,Object>(Map.of("waiting",300,"active",200,"busy",20,"slotsBusyOverTtl",7,"expiredByBusyCap",0L,"admittedThisTick",20,
                "endpoints",Map.of("queue.status",Map.of("rps",150,"latency",Map.of("p95",.5)))));
        var frame=measurements.sample(1000,server,Map.of(),queue,stats.sampleLive(false),stats.clientMetrics());
        assertThat(((Map<?,?>)frame.series().get("queue")).get("expiredByBusyCap")).isEqualTo(0L);
        assertThat(frame.events()).noneSatisfy(event -> assertThat(event.get("type")).isEqualTo("BUSY_CAP_EXPIRED"));
        queue.put("expiredByBusyCap",2L);
        frame=measurements.sample(2000,server,Map.of(),queue,stats.sampleLive(false),stats.clientMetrics());
        assertThat(frame.events()).singleElement().satisfies(event -> assertThat(event).containsEntry("type","BUSY_CAP_EXPIRED").containsEntry("count",2L).containsEntry("t",2L));
        assertThat(measurements.sample(3000,server,Map.of(),queue,stats.sampleLive(false),stats.clientMetrics()).events()).isEmpty();
    }
}
