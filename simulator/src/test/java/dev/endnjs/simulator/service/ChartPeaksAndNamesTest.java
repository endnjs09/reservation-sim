package dev.endnjs.simulator.service;

import dev.endnjs.simulator.engine.*;
import dev.endnjs.simulator.http.JsonCodec;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** 결과 그래프: 줄여 그려도 구간 최대값이 남는다. 요약: 최대 풀 사용률 그 초의 풀 대기. 엔드포인트 이름: depositPay·cancel 하나씩. */
class ChartPeaksAndNamesTest {
    @TempDir Path temp;
    private static Map<String,Object> row(long t,double rps,double errPct,double poolPct,long pending) {
        return Map.of("t",t,"wallMs",t*1000,"phase","RESALE","signals",Map.of("rps",rps,"p95",3.0,"errPct",errPct,"poolPct",poolPct,"poolPending",pending));
    }
    @Test void downsampledSeriesKeepsTheBucketMaximumNextToTheAverage() throws Exception {
        var store=new RunStore(temp.resolve("runs"),temp.resolve("old"),50);
        String id=store.create(new LinkedHashMap<>(Map.of("schemaVersion",5,"status","RUNNING","startedAt","2026-10-06T00:00:00Z","pinned",false,"timeScale",1)));
        store.append(id,"timeseries.ndjson",row(532,60,1,10,0));
        store.append(id,"timeseries.ndjson",row(533,70,2,85,0)); // 1초짜리 순간 최대
        store.append(id,"timeseries.ndjson",row(534,50,40,10,3));
        store.append(id,"timeseries.ndjson",row(535,60,1,10,0));
        var rows=store.series(id,"signals",4);assertThat(rows).hasSize(1);
        var signals=RunStore.object(rows.getFirst().get("signals"));
        assertThat(signals).containsEntry("poolPct",28.75); // 9.4: N초 평균은 그대로
        assertThat(signals).containsEntry("poolPctMax",85.0).containsEntry("errPctMax",40.0).containsEntry("rpsMax",70.0);
        // step=1이면 원래 값 그대로라 따로 두지 않는다
        assertThat(RunStore.object(store.series(id,"signals",1).getFirst().get("signals"))).doesNotContainKey("poolPctMax");
    }
    @Test void summaryRecordsPoolWaitAtTheSecondOfMaximumPoolUsage() {
        var config=new JsonCodec().config("{\"users\":1,\"timeScale\":1}");
        var stats=new RunStats(config,RunTime.system(),List.of(Persona.FAST));var measurements=new RunMeasurements(config);
        for(var p:List.of(new long[]{10,0},new long[]{85,0},new long[]{60,4},new long[]{20,1})) {
            var server=Map.<String,Object>of("phase","RESALE","pool",Map.of("active",p[0]/5,"max",20,"pending",p[1]));
            measurements.sample(1000,server,Map.of(),Map.of(),stats.sampleLive(false),stats.clientMetrics());
        }
        var summary=measurements.finish(stats.summary(),Map.of(),stats.clientMetrics());
        assertThat(summary.signals()).containsEntry("poolPctMax",85.0).containsEntry("poolPendingAtMax",0L);
    }
    @Test void errorRateCountsDepositPayAndCancelOnceUnderEitherNaming() {
        var config=new JsonCodec().config("{\"users\":1,\"timeScale\":1}");
        var stats=new RunStats(config,RunTime.system(),List.of(Persona.FAST));
        var oneName=Map.<String,Object>of("phase","RESALE","endpoints",Map.of(
                "depositPay",Map.of("status",Map.of("2xx",1L,"409",1L)),"cancel",Map.of("status",Map.of("2xx",2L))));
        var bothNames=Map.<String,Object>of("phase","RESALE","endpoints",Map.of( // 예전 서버: 같은 값이 두 이름으로
                "depositPay",Map.of("status",Map.of("2xx",1L,"409",1L)),"cancel",Map.of("status",Map.of("2xx",2L)),
                "deposit.pay",Map.of("status",Map.of("2xx",1L,"409",1L)),"reservation.cancel",Map.of("status",Map.of("2xx",2L))));
        for(var server:List.of(oneName,bothNames))
            assertThat(new RunMeasurements(config).sample(1000,server,Map.of(),Map.of(),stats.sampleLive(false),stats.clientMetrics()).signals().get("errPct")).isEqualTo(25.0);
    }
}
