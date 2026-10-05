package dev.endnjs.simulator.service;

import dev.endnjs.simulator.engine.*;
import dev.endnjs.simulator.http.JsonCodec;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api")
public class SimulatorController {
    private final RunService runs;
    private final PresetStore presets;
    private final StreamService stream;
    private final JsonCodec json = new JsonCodec();
    public SimulatorController(RunService runs, PresetStore presets, StreamService stream) {
        this.runs = runs; this.presets = presets; this.stream = stream;
    }
    private RunConfig config(String body) {
        try { return json.config(body); }
        catch (RuntimeException invalid) { throw new ApiFailure(400, "INVALID_CONFIG", "잘못된 설정: " + invalid.getMessage()); }
    }
    @PostMapping("/runs") public ResponseEntity<RunService.Current> start(@RequestBody String body) {
        return ResponseEntity.accepted().body(runs.start(config(body)));
    }
    @PostMapping("/runs/current/stop") public RunService.Current stop() { return runs.stop(); }
    @GetMapping("/runs/current") public RunService.Current current() { return runs.current(); }
    @GetMapping("/defaults") public RunConfig defaults() { return RunConfig.defaults(); }
    @GetMapping("/runs") public Map<String,Object> history() throws IOException { return runs.store().list(); }
    @GetMapping("/runs/{id}") public Map<String,Object> run(@PathVariable String id) throws IOException { return runs.store().get(id); }
    @GetMapping("/runs/{id}/summary") public Summary summary(@PathVariable String id) { return runs.summary(id); }
    @PatchMapping("/runs/{id}") public Map<String,Object> patch(@PathVariable String id,@RequestBody Map<String,Object> changes) throws IOException { return runs.store().patch(id,changes); }
    @DeleteMapping("/runs/{id}") public Map<String,Boolean> delete(@PathVariable String id) throws IOException { runs.store().delete(id);return Map.of("deleted",true); }
    @GetMapping("/runs/{id}/timeseries") public List<Map<String,Object>> series(@PathVariable String id,@RequestParam(required=false) String fields,@RequestParam(defaultValue="1") int step) throws IOException { return runs.store().series(id,fields,step); }
    @GetMapping("/runs/{id}/events") public List<Map<String,Object>> events(@PathVariable String id) throws IOException { return runs.store().events(id); }
    @GetMapping("/runs/{id}/invariants") public Map<String,Object> invariants(@PathVariable String id) throws IOException { return runs.store().invariants(id); }
    @GetMapping("/compare") public Map<String,Object> compare(@RequestParam String a,@RequestParam String b) throws IOException {
        Long ia=null,ib=null;
        try { ia=RunStore.passed(runs.store().invariants(a)); } catch(ApiFailure absent) { if(absent.status!=404) throw absent; }
        try { ib=RunStore.passed(runs.store().invariants(b)); } catch(ApiFailure absent) { if(absent.status!=404) throw absent; }
        return RunComparison.compare(runs.store().get(a),runs.store().get(b),ia,ib,runs.store().hasEvent(a,"SWAP_USED"),runs.store().hasEvent(b,"SWAP_USED"));
    }
    @GetMapping("/presets") public List<String> presets() throws IOException { return presets.list(); }
    @GetMapping("/presets/{name}") public RunConfig preset(@PathVariable String name) throws IOException { return presets.get(name); }
    @PutMapping("/presets/{name}") public RunConfig save(@PathVariable String name, @RequestBody String body) throws IOException {
        return presets.save(name, config(body));
    }
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter stream() { return stream.subscribe(); }

    @RestControllerAdvice(assignableTypes = SimulatorController.class)
    public static class Errors {
        @ExceptionHandler(ApiFailure.class) public ResponseEntity<Map<String,Object>> api(ApiFailure failure) {
            var body=new LinkedHashMap<String,Object>();body.put("code",failure.code);body.put("message",failure.getMessage());
            if(failure instanceof RunService.TargetDown down) body.put("target",down.target);
            return ResponseEntity.status(failure.status).body(body);
        }
        @ExceptionHandler(IOException.class) public ResponseEntity<Map<String, String>> storage(IOException failure, HttpServletResponse response) {
            // A disconnected SSE client cannot receive a JSON error after the stream is committed.
            if (response.isCommitted()) return null;
            return ResponseEntity.internalServerError().body(Map.of("code", "STORAGE_ERROR", "message", "파일을 읽거나 저장하지 못했습니다. 서버 로그를 확인하세요."));
        }
        @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException failure) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_CONFIG", "message", "잘못된 설정 파일: " + failure.getMessage()));
        }
    }
}
