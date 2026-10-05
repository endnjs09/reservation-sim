package dev.endnjs.simulator.service;

import dev.endnjs.simulator.engine.*;
import dev.endnjs.simulator.http.JsonCodec;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class W2StoreTest {
    @TempDir Path temp;
    private final JsonCodec json=new JsonCodec();
    private Map<String,Object> meta(String startedAt,boolean pinned) {
        return new LinkedHashMap<>(Map.of("schemaVersion",5,"status","RUNNING","startedAt",startedAt,"pinned",pinned,"timeScale",1,"queueMode","EMBEDDED","server",Map.of("strategy","conditional")));
    }
    private Summary summary() { return new RunEngine(new JsonCodec().config("{\"users\":1}"),r -> new HttpTransport.Response(500,Map.of())).run(); }
    @Test void finalReopenEventsUseDurableBatchTimesAndSeatsInsteadOfMissedWindowCounters() throws Exception {
        var store=new RunStore(temp.resolve("runs"),temp.resolve("old"),50);String id=store.create(meta("2026-10-04T00:00:00Z",false));
        var original=summary();var summary=new Summary(original.startedAt(),10000,original.config(),original.requests(),original.responses(),original.avgLatencyMs(),original.outcomes(),original.outcomesByPersona(),original.events(),original.outcomesByChurn(),original.seatsSold(),original.seatsByGrade());
        store.append(id,"events.ndjson",Map.of("t",9,"type","REOPEN","seats",0,"revisits",2));
        store.finish(id,summary,Map.of("server",Map.of("release_batches",List.of(Map.of("reopened",true,"seat_count",3,"released_at",summary.startedAt().plusSeconds(2).toString())))),"COMPLETED",null);
        assertThat(store.events(id)).hasSize(1);assertThat(store.events(id).getFirst()).containsEntry("t",8).containsEntry("seats",3).containsEntry("revisits",2);
    }
    @Test void storesFiveFilesPersistsPatchAndReadsAfterRestartWithoutInventingInvariants() throws Exception {
        var store=new RunStore(temp.resolve("runs"),temp.resolve("old"),50);String id=store.create(meta("2026-10-04T00:00:00Z",false));
        assertThat(id).matches("run-[0-9a-f]{8}");store.append(id,"timeseries.ndjson",Map.of("t",1,"signals",Map.of("p95",12)));
        store.append(id,"events.ndjson",Map.of("t",1,"type","PHASE","to","RUSH"));store.finish(id,summary(),Map.of("quiesced",true),"COMPLETED",null);
        store.patch(id,Map.of("label","baseline","notes","memo","pinned",true));
        try(var files=Files.list(temp.resolve("runs").resolve(id))) { assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactlyInAnyOrder("run.json","summary.json","timeseries.ndjson","events.ndjson","snapshot.json"); }
        var reloaded=new RunStore(temp.resolve("runs"),temp.resolve("old"),50);assertThat(reloaded.get(id)).containsEntry("label","baseline").containsEntry("pinned",true);
        var item=RunStore.object(((List<?>)reloaded.list().get("runs")).getFirst());assertThat(item).containsEntry("hasInvariants",false).containsEntry("invariantsPassed",null);
        assertThatThrownBy(() -> reloaded.invariants(id)).isInstanceOf(ApiFailure.class);assertThat(reloaded.events(id)).hasSize(1);
    }
    @Test void downsamplingAveragesSignalsAndTakesMaxPercentilesIncludingNestedClientP95() throws Exception {
        var store=new RunStore(temp.resolve("runs"),temp.resolve("old"),50);String id=store.create(meta("2026-10-04T00:00:00Z",false));
        store.append(id,"timeseries.ndjson",Map.of("t",0,"wallMs",0,"phase","RUSH","signals",Map.of("rps",10,"p95",50,"poolPct",40),"client",Map.of("p95",Map.of("holds",20))));
        store.append(id,"timeseries.ndjson",Map.of("t",1,"wallMs",1000,"phase","RUSH","signals",Map.of("rps",30,"p95",10,"poolPct",80),"client",Map.of("p95",Map.of("holds",10))));
        var rows=store.series(id,"signals,client",2);assertThat(rows).hasSize(1);assertThat(RunStore.object(rows.getFirst().get("signals"))).containsEntry("rps",20.0).containsEntry("p95",50.0).containsEntry("poolPct",60.0);
        assertThat(RunStore.object(RunStore.object(rows.getFirst().get("client")).get("p95"))).containsEntry("holds",20.0);
        assertThatThrownBy(() -> store.series(id,"garbage",1)).isInstanceOf(ApiFailure.class);assertThatThrownBy(() -> store.series(id,"",0)).isInstanceOf(ApiFailure.class);
    }
    @Test void legacyAndCorruptFilesGiveWarningsAndNeverExposePaths() throws Exception {
        Path old=temp.resolve("old");Files.createDirectories(old);var summary=summary();String id=UUID.randomUUID().toString();json.writeSummary(old.resolve(id+".json"),summary);Files.writeString(old.resolve("broken.json"),"{");
        var store=new RunStore(temp.resolve("runs"),old,50);var listed=store.list();assertThat(listed.get("warnings")).isEqualTo(List.of("broken.json"));assertThat((List<?>)listed.get("runs")).hasSize(1);
        assertThat(store.get(id)).containsEntry("schemaVersion",4);assertThat(store.summary(id).outcomes()).isEqualTo(summary.outcomes());assertThatThrownBy(() -> store.get("../../secret")).isInstanceOf(ApiFailure.class);
    }
    @Test void retentionKeepsPinnedAndActiveDeletionIsRejected() throws Exception {
        var store=new RunStore(temp.resolve("runs"),temp.resolve("old"),2);String pinned=store.create(meta("2026-10-04T00:00:00Z",true));store.finish(pinned,summary(),Map.of(),"COMPLETED",null);
        String old=store.create(meta("2026-10-04T00:00:01Z",false));store.finish(old,summary(),Map.of(),"COMPLETED",null);
        String latest=store.create(meta("2026-10-04T00:00:02Z",false));assertThatThrownBy(() -> store.delete(latest)).isInstanceOf(ApiFailure.class);store.finish(latest,summary(),Map.of(),"COMPLETED",null);
        assertThat(store.get(pinned)).isNotEmpty();assertThatThrownBy(() -> store.get(old)).isInstanceOf(ApiFailure.class);assertThat(store.get(latest)).isNotEmpty();
    }
    @Test void restartMarksIncompleteProcessFailedAndReadsOnlyPartialLastLine() throws Exception {
        Path dir=temp.resolve("runs");var store=new RunStore(dir,temp.resolve("old"),50);String id=store.create(meta("2026-10-04T00:00:00Z",false));
        store.append(id,"timeseries.ndjson",Map.of("t",0,"phase","OPEN"));Files.writeString(dir.resolve(id).resolve("timeseries.ndjson"),"{\"t\":",StandardOpenOption.APPEND);
        var reloaded=new RunStore(dir,temp.resolve("old"),50);assertThat(reloaded.get(id)).containsEntry("status","FAILED").containsEntry("statusReason","PROCESS_INTERRUPTED");assertThat(reloaded.series(id,"",1)).hasSize(1);
        Files.writeString(dir.resolve(id).resolve("timeseries.ndjson"),"\n",StandardOpenOption.APPEND);
        assertThatThrownBy(() -> reloaded.series(id,"",1)).isInstanceOf(java.io.IOException.class);
    }
    /** 3장·10.4: anchorAt·clockOffsetsMs·environment.bench 기록과 사건 조회. */
    @Test void clockBenchAndEventLookupAreRecordedInRunJson() throws Exception {
        var store=new RunStore(temp.resolve("runs"),temp.resolve("old"),50);var meta=meta("2026-10-04T00:00:00Z",false);meta.put("environment",Map.of("cpuCores",12,"os","Linux"));String id=store.create(meta);
        store.clock(id,java.time.Instant.parse("2026-10-04T00:00:01Z"),Map.of("server",3L,"queue",5L,"mockPg",2L));
        store.environment(id,"bench",Map.of("ports",Map.of("server",18180)));
        var run=store.get(id);
        assertThat(run).containsEntry("startedAt","2026-10-04T00:00:01Z").containsEntry("anchorAt","2026-10-04T00:00:01Z");
        assertThat(RunStore.object(run.get("clockOffsetsMs"))).containsEntry("queue",5);
        assertThat(RunStore.object(run.get("environment"))).containsEntry("cpuCores",12).containsKey("bench");
        assertThat(store.hasEvent(id,"SWAP_USED")).isFalse();
        store.append(id,"events.ndjson",Map.of("t",3,"type","SWAP_USED","swapUsedMb",150));
        assertThat(store.hasEvent(id,"SWAP_USED")).isTrue();assertThat(store.hasEvent("run-00000000","SWAP_USED")).isFalse();
    }
    /** 8.5: 읽지 못한 실행 기록은 목록에서 빼고 warnings에 그 파일 이름을 남긴다. */
    @Test void brokenRunJsonIsNamedInWarnings() throws Exception {
        var store=new RunStore(temp.resolve("runs"),temp.resolve("old"),50);String id=store.create(meta("2026-10-04T00:00:00Z",false));
        var broken=temp.resolve("runs").resolve("run-0badf00d");java.nio.file.Files.createDirectories(broken);java.nio.file.Files.writeString(broken.resolve("run.json"),"{not json");
        var listed=store.list();
        assertThat(listed.get("warnings")).isEqualTo(List.of("run-0badf00d/run.json"));
        assertThat(((List<?>)listed.get("runs")).stream().map(r -> RunStore.object(r).get("runId"))).containsExactly(id);
    }
}
