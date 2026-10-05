package dev.endnjs.simulator.service;

import dev.endnjs.simulator.http.JsonCodec;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** SPEC 1.0.2~1.1.2에서 바뀐 계약: 8.5 memAvailableMbAtStart, 11.9 connections, 10.3·9.1 earlyQuitRate. */
@SuppressWarnings("unchecked")
class W3SpecAlignmentTest {
    private final JsonCodec json=new JsonCodec();

    @Test void meminfoGivesAvailableAndSwapUsedInMegabytes() {
        var memory=HostMemory.parse("MemTotal:       20480000 kB\nMemAvailable:    8396800 kB\nSwapTotal:       4194304 kB\nSwapFree:        4091904 kB\n");
        assertThat(memory.availableMb()).isEqualTo(8200);
        assertThat(memory.swapUsedMb()).isEqualTo(100);
    }
    @Test void unreadableMeminfoGivesNullInsteadOfAFakeNumber() {
        var memory=HostMemory.read(java.nio.file.Path.of("does-not-exist/meminfo"));
        assertThat(memory.availableMb()).isNull();assertThat(memory.swapUsedMb()).isNull();
    }
    @Test void connectionCarriesSpecFieldsOkAndLastOkAtNextToTheExistingOnes() {
        var at=Instant.parse("2026-10-04T00:00:00Z");
        var up=RunStore.object(json.decode(json.encode(new ServerMetricsPoller.Connection(true,"http://x",at,at,null))));
        assertThat(up).containsEntry("ok",true).containsEntry("lastOkAt",at.toString()).containsEntry("connected",true).containsKey("error");
        var down=RunStore.object(json.decode(json.encode(new ServerMetricsPoller.Connection(false,"http://x",at,null,"timeout"))));
        assertThat(down).containsEntry("ok",false).containsEntry("lastOkAt",null).containsEntry("error","timeout");
    }
    @Test void removedEarlyQuitRateIsAcceptedFromOldPresetsButNotKept() {
        var config=json.config("{\"earlyQuitRate\":0.3}");
        assertThat(RunStore.object(json.decode(json.encode(config)))).doesNotContainKey("earlyQuitRate");
    }
    @Test void recordSavedWithEarlyQuitRateStillMatchesTheSameScenarioWithoutIt() {
        var current=run("conditional",null);
        var legacy=run("pessimistic",0.1);
        assertThat(legacy.get("fingerprint")).isNotEqualTo(current.get("fingerprint"));
        var result=RunComparison.compare(legacy,current,null,null);
        assertThat(result.get("sameScenario")).isEqualTo(true);
        assertThat((List<Object>)result.get("warnings")).doesNotContain("SCENARIO_DIFFERS");
        assertThat(((List<?>)result.get("configDiff")).stream().map(d -> RunStore.object(d).get("key"))).containsExactly("server.strategy");
    }
    @Test void legacyRecordWithADifferentScenarioStillDiffers() {
        var current=run("conditional",null);
        var legacy=run("pessimistic",0.1);var config=RunStore.object(legacy.get("config"));config.put("users",4000);legacy.put("config",config);
        assertThat((List<Object>)RunComparison.compare(legacy,current,null,null).get("warnings")).contains("SCENARIO_DIFFERS");
    }

    @Test void swapUsedIsReportedOnceAboveOneHundredMegabytes() {
        var used=new java.util.concurrent.atomic.AtomicLong(50);
        var watch=new SwapWatch(() -> new HostMemory(8000L,used.get()));
        assertThat(watch.check(10)).isNull();
        used.set(150);
        assertThat(watch.check(11)).containsEntry("type","SWAP_USED").containsEntry("t",11L).containsEntry("swapUsedMb",150L);
        used.set(300);assertThat(watch.check(12)).isNull();
        assertThat(new SwapWatch(() -> new HostMemory(null,null)).check(1)).isNull();
    }
    @Test void benchProfileIsVerifiedAgainstActualHeaps() {
        assertThat(BenchProfile.xmxMb("-Xms2g -Xmx2g")).isEqualTo(2048);assertThat(BenchProfile.xmxMb("-Xmx512m")).isEqualTo(512);assertThat(BenchProfile.xmxMb(null)).isNull();
        Map<String,Object> profile=Map.of("heap",Map.of("server","-Xms2g -Xmx2g","simulator","-Xmx2g"));
        var ok=BenchProfile.verify(profile,2048.0,2048L*1024*1024);
        assertThat((List<?>)ok.get("mismatch")).isEmpty();assertThat(RunStore.object(ok.get("verified"))).containsEntry("serverHeapMaxMb",2048.0);
        var wrong=BenchProfile.verify(profile,1024.0,2048L*1024*1024);
        assertThat(wrong.get("mismatch")).isEqualTo(List.of("server"));
        assertThat(BenchProfile.verify(null,1.0,1)).isNull();
        assertThat(BenchProfile.read((String)null)).isNull();assertThat(BenchProfile.read("does-not-exist.json")).containsKey("error");
    }
    @Test void clockOffsetIsServerTimeMinusAnchoredLocalTime() throws Exception {
        var anchor=new dev.endnjs.simulator.engine.AnchorTime.Anchor(Instant.parse("2026-10-04T00:00:00Z"),System.nanoTime());
        dev.endnjs.simulator.engine.HttpTransport transport=new dev.endnjs.simulator.engine.HttpTransport() {
            public Response exchange(Request request) {
                Instant local=anchor.at().plusNanos(System.nanoTime()-anchor.nanos());
                return new Response(200,Map.of("serverTime",local.plusMillis(2000).toString()));
            }
            public void close() {}
        };
        assertThat(RunService.clockOffsetMs(transport,dev.endnjs.simulator.engine.HttpTransport.Target.QUEUE,anchor)).isBetween(1950L,2050L);
        dev.endnjs.simulator.engine.HttpTransport noTime=new dev.endnjs.simulator.engine.HttpTransport() {
            public Response exchange(Request request) { return new Response(200,Map.of()); }
            public void close() {}
        };
        assertThat(RunService.clockOffsetMs(noTime,dev.endnjs.simulator.engine.HttpTransport.Target.PG,anchor)).isNull();
    }

    /** earlyQuit가 있으면 예전 방식(fingerprint에 포함)으로 저장된 기록을 흉내 낸다. */
    private Map<String,Object> run(String strategy,Double earlyQuit) {
        var config=RunStore.object(json.decode(json.encode(json.config("{\"timeScale\":1,\"strategy\":\""+strategy+"\"}"))));
        String fingerprint=RunComparison.fingerprint(config);
        if(earlyQuit!=null) {
            config.put("earlyQuitRate",earlyQuit);
            var scenario=new TreeMap<>(config);
            for(String key:List.of("strategy","dbBackstop","label","notes","thresholds","targets","requestTimeoutMs","busyMaxExtraSec","queueMode")) scenario.remove(key);
            fingerprint="sha256:legacy-"+json.encode(scenario).hashCode();
        }
        var result=new LinkedHashMap<String,Object>();result.put("runId","run-0000000"+(earlyQuit==null ? "1" : "2"));result.put("schemaVersion",5);result.put("status","COMPLETED");
        result.put("timeScale",1);result.put("queueMode","EXTERNAL");result.put("config",config);
        result.put("server",Map.of("strategy",strategy,"dbBackstop",true,"poolMax",20,"threadsMax",200,"virtualThreads",false));
        result.put("environment",Map.of("cpuCores",12,"os","Linux"));result.put("fingerprint",fingerprint);
        result.put("signals",Map.of("p95Max",100,"poolSatSec",0,"rpsMax",100));result.put("seatsSold",100);return result;
    }
}
