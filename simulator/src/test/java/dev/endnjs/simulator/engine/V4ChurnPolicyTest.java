package dev.endnjs.simulator.engine;

import java.util.Map;
import java.util.SplittableRandom;
import dev.endnjs.simulator.http.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class V4ChurnPolicyTest {
    private RunConfig config(Map<String,Object> changes) { var json=new JsonCodec(); return json.config(json.encode(changes)); }

    @ParameterizedTest @ValueSource(ints={1,2,4})
    void casualTimerStartsAtFirstEmptyObservationAndResetsOnAvailableSeat(int scale) {
        var policy=new ChurnPolicy(ChurnPolicy.Type.casual,config(Map.of("timeScale",scale,"casualLeaveSec",Map.of("min",60,"max",60))),new SplittableRandom(42));
        long first=10_000_000_000L,wait=60_000_000_000L/scale;
        assertThat(policy.leave(first)).isFalse();
        assertThat(policy.leave(first+wait-1)).isFalse();
        assertThat(policy.leave(first+wait)).isTrue();
        policy.reset();
        assertThat(policy.leave(first+wait+1)).isFalse();
    }

    @Test void casualUniformDistributionHasExpectedRangeAndMean() {
        var config=config(Map.of("timeScale",1)); double sum=0;
        for(int i=0;i<20000;i++) {
            var policy=new ChurnPolicy(ChurnPolicy.Type.casual,config,new SplittableRandom(i));
            long seconds=0; while(!policy.leave(seconds*1_000_000_000L)) seconds++;
            assertThat(seconds).isBetween(0L,60L); sum+=seconds;
        }
        assertThat(sum/20000).isCloseTo(30.5,within(.5));
    }

    @ParameterizedTest @ValueSource(ints={1,2,4})
    void persistentHalfLifeLeavesApproximatelyHalfAtEquivalentSimulationInterval(int scale) {
        var config=config(Map.of("timeScale",scale)); int departed=0, samples=100000;
        for(int i=0;i<samples;i++) {
            var policy=new ChurnPolicy(ChurnPolicy.Type.persistent,config,new SplittableRandom(i));
            assertThat(policy.leave(0)).isFalse(); if(policy.leave(180_000_000_000L/scale)) departed++;
        }
        assertThat(departed/(double)samples).isCloseTo(.5,within(.008));
    }

    @Test void hardcoreNeverLeavesEmptyInventory() {
        var policy=new ChurnPolicy(ChurnPolicy.Type.hardcore,RunConfig.defaults(),new SplittableRandom(42));
        for(long seconds=0;seconds<=1200;seconds++) assertThat(policy.leave(seconds*1_000_000_000L)).isFalse();
    }

    @Test void profilesReproduceChurnMixAndPerUserChoice() {
        var config=RunConfig.defaults(); int[] counts=new int[3];
        for(int i=0;i<100000;i++) {
            var first=VirtualUser.profile(config,i);var second=VirtualUser.profile(config,i);
            assertThat(first.churn()).isEqualTo(second.churn()); counts[first.churn().ordinal()]++;
        }
        assertThat(counts[0]/100000.0).isCloseTo(.7,within(.01));
        assertThat(counts[1]/100000.0).isCloseTo(.2,within(.01));
        assertThat(counts[2]/100000.0).isCloseTo(.1,within(.01));
    }

    @Test void removedStandbySettingIsRejectedAndCloseOptionDefaultsTrue() {
        var json=new JsonCodec();
        assertThat(json.config("{}").closeQueueOnSoldOut()).isTrue(); // 2026-10-05 기본값 켬 (docs/DECISION_CLAUDE.md)
        assertThat(json.config("{\"closeQueueOnSoldOut\":false}").closeQueueOnSoldOut()).isFalse();
        assertThatThrownBy(() -> json.config("{\"standbyLimit\":100}")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> config(Map.of("persistentHalfLifeSec",0))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> config(Map.of("churnMix",Map.of("casual",100,"persistent",100,"hardcore",0)))).isInstanceOf(RuntimeException.class);
    }
    @Test void oldSavedSummaryWithStandbySettingRemainsReadable(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var json=new JsonCodec();
        var saved=new java.util.LinkedHashMap<String,Object>(json.response(json.encode(RunConfig.defaults())));
        saved.put("standbyLimit",100);
        saved.remove("closeQueueOnSoldOut");saved.remove("timeScale");saved.remove("queueMode");
        var summary=Map.of("startedAt","2026-10-03T00:00:00Z","durationMs",12,"config",saved,
                "requests",Map.of("sent",1,"byEndpoint",Map.of("queue.enter",1)),"responses",Map.of("2xx",1),
                "avgLatencyMs",Map.of("queue.enter",12),"outcomes",Map.of("confirmed",1,"incomplete",0),
                "outcomesByPersona",Map.of(),"events",Map.of());
        var path=dir.resolve("legacy-summary.json");java.nio.file.Files.writeString(path,json.encode(summary));
        var restored=json.readSummary(path);
        assertThat(restored.outcomes()).containsEntry("confirmed",1L);
        assertThat(restored.config().closeQueueOnSoldOut()).isFalse();
        assertThat(restored.config().timeScale()).isEqualTo(1);
        assertThat(restored.config().queueMode()).isEqualTo("EMBEDDED");
        assertThat(json.response(json.encode(restored.config()))).doesNotContainKey("standbyLimit");
    }

    @Test void departedUsersShareOneInternalStatsPollPerSecondWithoutUserMeasurements() throws Exception {
        var nanos=new java.util.concurrent.atomic.AtomicLong();
        RunTime time=new RunTime() {
            public java.time.Instant instant() { return java.time.Instant.parse("2026-10-03T00:00:00Z").plusNanos(nanos.get()); }
            public long nanoTime() { return nanos.get(); }
            public void sleep(long millis) { nanos.addAndGet(millis*1_000_000); }
        };
        var config=config(Map.of("users",1));var control=new RunControl(time);control.start(600);
        var stats=new RunStats(config,time,java.util.List.of(Persona.FAST));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var http=new RunHttp(request -> {
            assertThat(request.path()).isEqualTo("/admin/stats");calls.incrementAndGet();
            return new HttpTransport.Response(200,Map.of("saleEndAt",time.instant().plusSeconds(300).toString(),
                    "releaseBatches",Map.of("nextReleaseAt",time.instant().plusSeconds(10).toString())));
        },control,stats);
        var schedule=new RevisitSchedule(http,time,config);schedule.start(time.instant());
        var first=schedule.signal();for(int i=0;i<100;i++) assertThat(schedule.signal()).isEqualTo(first);
        assertThat(calls.get()).isEqualTo(1);time.sleep(1000);schedule.signal();assertThat(calls.get()).isEqualTo(2);
        assertThat(stats.summary().requests().sent()).isZero();assertThat(stats.summary().responses().get("2xx")).isZero();
    }

}
