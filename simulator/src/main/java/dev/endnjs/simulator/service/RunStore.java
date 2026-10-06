package dev.endnjs.simulator.service;

import dev.endnjs.simulator.engine.Summary;
import dev.endnjs.simulator.http.JsonCodec;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Stores facts from a run; external invariant results are never created here. */
public final class RunStore {
    private final Path directory,legacy;
    private final int keep;
    private final JsonCodec json=new JsonCodec();
    public RunStore(Path directory,Path legacy,int keep) throws IOException {
        if(keep<1) throw new IllegalArgumentException("runs.keep must be positive");
        this.directory=directory.toAbsolutePath();this.legacy=legacy.toAbsolutePath();this.keep=keep;
        Files.createDirectories(this.directory);
        // A process which disappeared cannot leave an apparently live run after restart.
        for(Path path:directories()) {
            try {
                var run=readObject(path.resolve("run.json"));
                if("RUNNING".equals(run.get("status"))) {
                    run.put("status","FAILED");run.put("statusReason","PROCESS_INTERRUPTED");
                    run.put("endedAt",java.time.Instant.now().toString());write(path.resolve("run.json"),run);
                }
            } catch(IOException|RuntimeException invalid) { /* Listed as a warning when read. */ }
        }
    }
    public synchronized String create(Map<String,Object> run) throws IOException {
        String id;
        while(true) {
            id="run-"+UUID.randomUUID().toString().replace("-","").substring(0,8);
            try { Files.createDirectory(directory.resolve(id));break; } catch(FileAlreadyExistsException collision) { }
        }
        run.put("runId",id);write(directory.resolve(id).resolve("run.json"),run);
        Files.createFile(directory.resolve(id).resolve("timeseries.ndjson"));
        Files.createFile(directory.resolve(id).resolve("events.ndjson"));
        return id;
    }
    public synchronized void append(String id,String name,Object value) throws IOException {
        if(!Set.of("timeseries.ndjson","events.ndjson").contains(name)) throw new IllegalArgumentException("Invalid append file");
        // Closing the writer flushes the once-per-second append.
        try(var writer=Files.newBufferedWriter(path(id).resolve(name),StandardOpenOption.APPEND)) { writer.write(json.encode(value));writer.newLine(); }
    }
    public synchronized void finish(String id,Summary summary,Map<String,Object> snapshot,String status,String reason) throws IOException {
        Path path=path(id);var run=readObject(path.resolve("run.json"));
        run.put("status",status);run.put("statusReason",reason);run.put("endedAt",summary.startedAt().plusMillis(summary.durationMs()).toString());
        run.put("startedAt",summary.startedAt().toString());run.put("durationMs",summary.durationMs());run.put("simDurationSec",Math.round(summary.durationMs()/1000.0*summary.config().timeScale()));
        write(path.resolve("summary.json"),summary);write(path.resolve("snapshot.json"),snapshot);
        reconcileReopens(path,summary,snapshot);
        write(path.resolve("run.json"),run);prune();
    }
    /** Window counters can miss a batch between admin/metrics polls. Durable rows supply its facts. */
    private void reconcileReopens(Path path,Summary summary,Map<String,Object> snapshot) throws IOException {
        var server=object(snapshot.get("server"));if(!(server.get("release_batches") instanceof List<?> batches)) return;
        var original=lines(path.resolve("events.ndjson"));var events=new ArrayList<Map<String,Object>>();
        original.stream().filter(e -> !"REOPEN".equals(e.get("type"))).forEach(events::add);
        var observations=original.stream().filter(e -> "REOPEN".equals(e.get("type"))).toList();var used=new HashSet<Integer>();
        var end=summary.startedAt().plusMillis(summary.durationMs());
        for(Object value:batches) {
            var batch=object(value);if(!Boolean.TRUE.equals(batch.get("reopened")) || !(batch.get("released_at") instanceof String released)) continue;
            var at=java.time.Instant.parse(released);if(at.isBefore(summary.startedAt()) || at.isAfter(end)) continue;
            long t=java.time.Duration.between(summary.startedAt(),at).toMillis()*summary.config().timeScale()/1000;
            var event=new LinkedHashMap<String,Object>();event.put("t",t);event.put("type","REOPEN");event.put("seats",batch.get("seat_count"));event.put("revisits",null);
            int nearest=-1;long distance=Long.MAX_VALUE;
            for(int i=0;i<observations.size();i++) if(!used.contains(i)) {
                long gap=Math.abs(((Number)observations.get(i).get("t")).longValue()-t);
                if(gap<distance) { nearest=i;distance=gap; }
            }
            if(nearest>=0 && distance<=5L*summary.config().timeScale()) { event.put("revisits",observations.get(nearest).get("revisits"));used.add(nearest); }
            events.add(event);
        }
        events.sort(Comparator.comparingLong(e -> ((Number)e.get("t")).longValue()));
        JsonFiles.write(path.resolve("events.ndjson"),events.stream().map(json::encode).collect(java.util.stream.Collectors.joining("\n")));
    }
    /** 3장: startedAt = anchorAt (기준점 하나), clockOffsetsMs. */
    public synchronized void clock(String id,java.time.Instant anchorAt,Map<String,Object> offsets) throws IOException {
        Path path=path(id);var run=readObject(path.resolve("run.json"));run.put("startedAt",anchorAt.toString());run.put("anchorAt",anchorAt.toString());run.put("clockOffsetsMs",offsets);write(path.resolve("run.json"),run);
    }
    public synchronized void environment(String id,String key,Object value) throws IOException {
        Path path=path(id);var run=readObject(path.resolve("run.json"));var environment=new LinkedHashMap<>(object(run.get("environment")));environment.put(key,value);run.put("environment",environment);write(path.resolve("run.json"),run);
    }
    public synchronized void server(String id,Map<String,Object> actual) throws IOException {
        Path path=path(id);var run=readObject(path.resolve("run.json"));run.put("server",actual);write(path.resolve("run.json"),run);
    }
    public synchronized Map<String,Object> list() throws IOException {
        var runs=new ArrayList<Map<String,Object>>();var warnings=new ArrayList<String>();
        for(Path path:directories()) {
            try { runs.add(listItem(readObject(path.resolve("run.json")),path)); }
            catch(IOException|RuntimeException invalid) { warnings.add(path.getFileName()+"/run.json"); } // 8.5: 읽지 못한 파일 이름
        }
        for(Path path:legacyFiles()) {
            try { runs.add(listItem(legacy(path),null)); }
            catch(IOException|RuntimeException invalid) { warnings.add(path.getFileName().toString()); }
        }
        runs.sort(Comparator.comparing(r -> Objects.toString(r.get("startedAt"),""),Comparator.reverseOrder()));
        return Map.of("runs",runs,"warnings",warnings);
    }
    private Map<String,Object> listItem(Map<String,Object> run,Path path) throws IOException {
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("runId","label","notes","pinned","status","startedAt","durationMs","timeScale","queueMode","schemaVersion","fingerprint")) result.put(key,run.get(key));
        result.put("strategy",object(run.get("server")).get("strategy"));
        boolean exists=path!=null && Files.isRegularFile(path.resolve("invariants.json"),LinkOption.NOFOLLOW_LINKS);
        result.put("hasInvariants",exists);result.put("invariantsPassed",exists ? passed(readObject(path.resolve("invariants.json"))) : null);
        return result;
    }
    public synchronized Map<String,Object> get(String id) throws IOException {
        if(id.matches("[0-9a-f-]{36}")) {
            Path found=legacy.resolve(id+".json");if(!Files.isRegularFile(found,LinkOption.NOFOLLOW_LINKS)) throw missing();return legacy(found);
        }
        Path path=path(id);var result=new LinkedHashMap<String,Object>();
        if(Files.isRegularFile(path.resolve("summary.json"),LinkOption.NOFOLLOW_LINKS)) result.putAll(readObject(path.resolve("summary.json")));
        result.putAll(readObject(path.resolve("run.json")));return result;
    }
    public synchronized Summary summary(String id) throws IOException {
        return json.readSummary(id.matches("[0-9a-f-]{36}") ? legacy.resolve(id+".json") : path(id).resolve("summary.json"));
    }
    public synchronized Map<String,Object> patch(String id,Map<String,Object> changes) throws IOException {
        if(!Set.of("label","notes","pinned").containsAll(changes.keySet())) throw new ApiFailure(400,"INVALID_PATCH","수정할 수 없는 항목입니다.");
        Path path=path(id);var run=readObject(path.resolve("run.json"));
        changes.forEach((key,value) -> {
            if(key.equals("pinned") ? !(value instanceof Boolean) : !(value instanceof String text) || text.length()>(key.equals("label") ? 200 : 4000)) throw new ApiFailure(400,"INVALID_PATCH","잘못된 기록 값입니다.");
            run.put(key,value);
        });
        write(path.resolve("run.json"),run);return get(id);
    }
    public synchronized void delete(String id) throws IOException {
        if(id.matches("[0-9a-f-]{36}")) {
            if(!Files.deleteIfExists(legacy.resolve(id+".json"))) throw missing();return;
        }
        Path path=path(id);if("RUNNING".equals(readObject(path.resolve("run.json")).get("status"))) throw new ApiFailure(409,"RUN_ACTIVE","실행 중 기록은 삭제할 수 없습니다.");
        try(var files=Files.walk(path)) { for(Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
    }
    /** 10.4 ENV_DEGRADED 판단용. events.ndjson이 없는 예전 기록은 false. */
    public synchronized boolean hasEvent(String id,String type) {
        try { return lines(path(id).resolve("events.ndjson")).stream().anyMatch(e -> type.equals(e.get("type"))); }
        catch(IOException|RuntimeException absent) { return false; }
    }
    public synchronized List<Map<String,Object>> events(String id) throws IOException { return lines(path(id).resolve("events.ndjson")); }
    public synchronized Map<String,Object> invariants(String id) throws IOException {
        Path file=path(id).resolve("invariants.json");if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new ApiFailure(404,"INVARIANTS_NOT_FOUND","외부 검사 결과가 없습니다.");return readObject(file);
    }
    public synchronized List<Map<String,Object>> series(String id,String fields,int step) throws IOException {
        if(step<1 || step>86400) throw new ApiFailure(400,"INVALID_STEP","step 범위는 1~86400입니다.");
        Set<String> selected=fields==null || fields.isBlank() ? Set.of() : Set.of(fields.split(","));
        if(!Set.of("signals","server","queue","client","mockPg","users","seats","t","wallMs","phase").containsAll(selected)) throw new ApiFailure(400,"INVALID_FIELDS","알 수 없는 시계열 필드입니다.");
        var groups=new TreeMap<Long,List<Map<String,Object>>>();
        for(var line:lines(path(id).resolve("timeseries.ndjson"))) {
            long t=((Number)line.get("t")).longValue();groups.computeIfAbsent(t/step,k -> new ArrayList<>()).add(line);
        }
        var result=new ArrayList<Map<String,Object>>();
        groups.forEach((bucket,rows) -> {
            var row=aggregate(rows,"");row.put("t",bucket*step);
            // 9.4대로 N초 평균은 그대로 두고, 평균에 묻히는 1초짜리 최대를 구간 최대값으로 곁에 남긴다 (추가 필드)
            if(step>1 && row.get("signals") instanceof Map<?,?> aggregated) {
                var signals=new LinkedHashMap<String,Object>(object(aggregated));
                for(String key:List.of("rps","errPct","poolPct")) {
                    var values=rows.stream().map(r -> object(r.get("signals")).get(key)).filter(Number.class::isInstance).mapToDouble(v -> ((Number)v).doubleValue());
                    var max=values.max();if(max.isPresent()) signals.put(key+"Max",max.getAsDouble());
                }
                row.put("signals",signals);
            }
            if(!selected.isEmpty()) row.keySet().removeIf(k -> !selected.contains(k) && !Set.of("t","wallMs","phase").contains(k));
            result.add(row);
        });return result;
    }
    static Map<String,Object> aggregate(List<Map<String,Object>> rows,String prefix) {
        var result=new LinkedHashMap<String,Object>();var keys=new TreeSet<String>();rows.forEach(row -> keys.addAll(row.keySet()));
        for(String key:keys) {
            var values=rows.stream().map(r -> r.get(key)).filter(Objects::nonNull).toList();
            if(values.isEmpty()) { result.put(key,null);continue; }
            if(values.getFirst() instanceof Map) result.put(key,aggregate(values.stream().map(RunStore::object).toList(),key));
            else if(values.getFirst() instanceof Number) {
                var numbers=values.stream().filter(Number.class::isInstance).mapToDouble(v -> ((Number)v).doubleValue());
                boolean max=key.toLowerCase(Locale.ROOT).matches(".*(p50|p95|p99).*|max") || prefix.equals("p95");
                result.put(key,max ? numbers.max().orElse(0) : numbers.average().orElse(0));
            } else result.put(key,values.getFirst());
        }return result;
    }
    private void prune() throws IOException {
        var removable=new ArrayList<Map<String,Object>>();int count=0;
        for(Path path:directories()) {
            try { var run=readObject(path.resolve("run.json"));count++;if(!Boolean.TRUE.equals(run.get("pinned")) && !"RUNNING".equals(run.get("status"))) removable.add(run); }
            catch(IOException|RuntimeException invalid) { }
        }
        removable.sort(Comparator.comparing(r -> Objects.toString(r.get("startedAt"),"")));
        for(var run:removable) { if(count<=keep) break;delete((String)run.get("runId"));count--; }
    }
    private Path path(String id) {
        if(id==null || !id.matches("run-[0-9a-f]{8}")) throw missing();Path path=directory.resolve(id);
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) throw missing();return path;
    }
    private List<Path> directories() throws IOException {
        try(var paths=Files.list(directory)) { return paths.filter(p -> Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS) && p.getFileName().toString().matches("run-[0-9a-f]{8}")).toList(); }
    }
    private List<Path> legacyFiles() throws IOException {
        if(!Files.isDirectory(legacy)) return List.of();
        try(var paths=Files.list(legacy)) { return paths.filter(p -> Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) && p.getFileName().toString().endsWith(".json")).toList(); }
    }
    private Map<String,Object> legacy(Path file) throws IOException {
        String id=file.getFileName().toString().replaceFirst("\\.json$","");UUID.fromString(id);
        var summary=json.readSummary(file);var result=object(json.decode(json.encode(summary)));
        result.put("schemaVersion",4);result.put("runId",id);result.put("label","구버전 · 요약만");result.put("status",summary.outcomes().getOrDefault("incomplete",0L)>0 ? "STOPPED" : "COMPLETED");
        result.put("timeScale",summary.config().timeScale());result.put("queueMode","EMBEDDED");result.put("pinned",false);result.put("server",Map.of("strategy",summary.config().strategy()));return result;
    }
    private List<Map<String,Object>> lines(Path path) throws IOException {
        var result=new ArrayList<Map<String,Object>>();if(!Files.exists(path)) return result;
        String text=Files.readString(path);boolean terminated=text.endsWith("\n");var lines=text.lines().toList();
        for(int i=0;i<lines.size();i++) {
            try { result.add(readText(lines.get(i))); }
            catch(RuntimeException invalid) { if(i!=lines.size()-1 || terminated) throw new IOException("Corrupt series: "+path.getFileName(),invalid); }
        }return result;
    }
    private Map<String,Object> readObject(Path path) throws IOException { return readText(Files.readString(path)); }
    private Map<String,Object> readText(String text) { Object value=json.decode(text);if(!(value instanceof Map)) throw new IllegalArgumentException("Expected object");return object(value); }
    private void write(Path path,Object value) throws IOException { JsonFiles.write(path,json.encode(value)); }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value) { return value instanceof Map<?,?> map ? new LinkedHashMap<>((Map<String,Object>)map) : new LinkedHashMap<>(); }
    public static Long passed(Map<String,Object> invariants) {
        return invariants.get("results") instanceof List<?> results ? results.stream().filter(v -> Boolean.TRUE.equals(object(v).get("pass"))).count() : null;
    }
    private static ApiFailure missing() { return new ApiFailure(404,"RUN_NOT_FOUND","실행 기록이 없습니다."); }
}
