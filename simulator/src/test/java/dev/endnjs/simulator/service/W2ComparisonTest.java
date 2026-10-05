package dev.endnjs.simulator.service;

import dev.endnjs.simulator.http.JsonCodec;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class W2ComparisonTest {
    private Map<String,Object> run(String strategy) {
        var json=new JsonCodec();var config=RunStore.object(json.decode(json.encode(json.config("{\"timeScale\":1,\"strategy\":\""+strategy+"\"}"))));
        var result=new LinkedHashMap<String,Object>();result.put("runId","run-12345678");result.put("schemaVersion",5);result.put("status","COMPLETED");result.put("timeScale",1);result.put("queueMode","EMBEDDED");result.put("config",config);result.put("server",Map.of("strategy",strategy,"dbBackstop",true,"poolMax",20,"threadsMax",200,"virtualThreads",false));result.put("environment",Map.of("cpuCores",8,"os","Linux"));result.put("fingerprint",RunComparison.fingerprint(config));result.put("signals",Map.of("p95Max",100,"poolSatSec",0,"rpsMax",100));result.put("seatsSold",100);return result;
    }
    @Test void sameScenarioOnlyStrategyDiffersAndExcludedValuesStayExcluded() {
        var a=run("conditional");var b=run("pessimistic");var config=RunStore.object(b.get("config"));config.put("label","other");config.put("requestTimeoutMs",2500);config.put("targets",Map.of("server","http://other"));b.put("config",config);
        assertThat(RunComparison.fingerprint(config)).isEqualTo(a.get("fingerprint"));
        var result=RunComparison.compare(a,b,null,null);assertThat(result.get("warnings")).isEqualTo(List.of());assertThat(result.get("sameScenario")).isEqualTo(true);
        assertThat((List<?>)result.get("configDiff")).hasSize(1);assertThat((List<?>)result.get("metrics")).hasSize(14);
    }
    @Test void allEightWarningsAndNoSyntheticInvariantWinner() {
        var a=run("conditional");var b=run("pessimistic");b.put("fingerprint","different");b.put("clockOffsetsMs",Map.of("server",3));b.put("server",Map.of("strategy","pessimistic","poolMax",40));b.put("timeScale",4);b.put("status","FAILED");b.put("schemaVersion",4);b.put("environment",Map.of("cpuCores",4,"os","other"));
        var result=RunComparison.compare(a,b,null,null,false,true);assertThat((List<?>)result.get("warnings")).hasSize(8);
        assertThat(RunStore.object(result.get("warningNotes"))).containsKey("CLOCK_MODEL_DIFFERS");assertThat(result.get("comparable")).isEqualTo(false);
        var last=RunStore.object(((List<?>)result.get("metrics")).getLast());assertThat(last.get("a")).isNull();assertThat(last.get("winner")).isNull();
    }
    @ParameterizedTest
    @ValueSource(strings={"SCENARIO_DIFFERS","MULTIPLE_VARIABLES","TIME_SCALED","NOT_COMPLETED","OLD_SCHEMA","ENV_DIFFERS","CLOCK_MODEL_DIFFERS","ENV_DEGRADED"})
    void comparisonWarningsEachHaveTheirOwnBoundary(String warning) {
        var a=run("conditional");var b=run("pessimistic");
        switch(warning) {
            case "SCENARIO_DIFFERS" -> {
                var config=RunStore.object(b.get("config"));config.put("users",4000);b.put("config",config);
                b.put("fingerprint",RunComparison.fingerprint(config));
            }
            case "MULTIPLE_VARIABLES" -> {
                var server=RunStore.object(b.get("server"));server.put("poolMax",40);b.put("server",server);
            }
            case "TIME_SCALED" -> {
                for(var run:List.of(a,b)) {
                    var config=RunStore.object(run.get("config"));config.put("timeScale",4);run.put("config",config);
                    run.put("timeScale",4);run.put("fingerprint",RunComparison.fingerprint(config));
                }
            }
            case "NOT_COMPLETED" -> b.put("status","STOPPED");
            case "OLD_SCHEMA" -> b.put("schemaVersion",4);
            case "ENV_DIFFERS" -> b.put("environment",Map.of("cpuCores",4,"os","Linux"));
            case "CLOCK_MODEL_DIFFERS" -> b.put("clockOffsetsMs",Map.of("server",3,"queue",5,"mockPg",2));
            case "ENV_DEGRADED" -> {}
            default -> throw new AssertionError(warning);
        }
        var result=RunComparison.compare(a,b,null,null,false,warning.equals("ENV_DEGRADED"));
        assertThat(result.get("warnings")).isEqualTo(List.of(warning));
        assertThat(result.get("comparable")).isEqualTo(!warning.equals("OLD_SCHEMA"));
    }
    /** 10.4: bench 설정값(포트·힙·CPU·DB)이 다르면 ENV_DIFFERS. 관측값(verified·mismatch)은 비교하지 않는다. */
    @Test void benchSettingsDecideEnvDiffersButObservedHeapDoesNot() {
        var profile=Map.<String,Object>of("ports",Map.of("server",18180),"heap",Map.of("server","-Xms2g -Xmx2g"),"cpus",Map.of("server","0-5"),"db",Map.of("cpuset","6-11","memory","2g"));
        var a=run("conditional");var b=run("pessimistic");
        var benchA=new LinkedHashMap<String,Object>(profile);benchA.put("verified",Map.of("serverHeapMaxMb",2048.0));
        var benchB=new LinkedHashMap<String,Object>(profile);benchB.put("verified",Map.of("serverHeapMaxMb",2047.0));
        a.put("environment",Map.of("cpuCores",8,"os","Linux","bench",benchA));b.put("environment",Map.of("cpuCores",8,"os","Linux","bench",benchB));
        assertThat((List<?>)RunComparison.compare(a,b,null,null).get("warnings")).isEmpty();
        var other=new LinkedHashMap<String,Object>(profile);other.put("cpus",Map.of("server","0-3"));
        b.put("environment",Map.of("cpuCores",8,"os","Linux","bench",other));
        assertThat(RunComparison.compare(a,b,null,null).get("warnings")).isEqualTo(List.of("ENV_DIFFERS"));
        b.put("environment",Map.of("cpuCores",8,"os","Linux"));
        assertThat(RunComparison.compare(a,b,null,null).get("warnings")).isEqualTo(List.of("ENV_DIFFERS"));
        // 연결 대기 줄(accept backlog) 100 대 1024도 측정 환경 차이다 (W4 발견, 나중의 실험 변수)
        var backlog=new LinkedHashMap<String,Object>(profile);backlog.put("acceptCount",Map.of("server",1024,"queue",1024));
        var small=new LinkedHashMap<String,Object>(profile);small.put("acceptCount",Map.of("server",100,"queue",100));
        a.put("environment",Map.of("cpuCores",8,"os","Linux","bench",small));b.put("environment",Map.of("cpuCores",8,"os","Linux","bench",backlog));
        assertThat(RunComparison.compare(a,b,null,null).get("warnings")).isEqualTo(List.of("ENV_DIFFERS"));
    }
    @Test void zeroBaselineTieAndDisplayOnlyMetricsHaveNoWinner() {
        var a=run("conditional");var b=run("pessimistic");b.put("signals",Map.of("p95Max",150,"poolSatSec",10,"rpsMax",200));
        var result=RunComparison.compare(a,b,2L,3L);var metrics=((List<?>)result.get("metrics")).stream().map(RunStore::object).toList();
        assertThat(metrics.getFirst()).containsEntry("winner","a").containsEntry("diffPct",50.0);
        assertThat(metrics.get(8)).containsEntry("winner","a").containsEntry("diffPct",null);
        assertThat(metrics.get(10)).containsEntry("winner",null);assertThat(metrics.get(12)).containsEntry("winner",null);assertThat(metrics.get(13)).containsEntry("winner","b");
    }
    @Test void fingerprintCanonicalizesMapOrderAndIncludesScenarioParameters() {
        var a=new LinkedHashMap<String,Object>();a.put("users",100);a.put("arrival",Map.of("fromSec",0,"toSec",5));
        var b=new LinkedHashMap<String,Object>();b.put("arrival",new TreeMap<>(Map.of("toSec",5,"fromSec",0)));b.put("users",100);b.put("queueMode","EXTERNAL");
        assertThat(RunComparison.fingerprint(a)).isEqualTo(RunComparison.fingerprint(b));b.put("users",101);assertThat(RunComparison.fingerprint(a)).isNotEqualTo(RunComparison.fingerprint(b));
    }
}
