package dev.endnjs.queue;

import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class QueueController {
    private final QueueState state;private final QueueMetrics metrics;private final byte[] secret;private final java.time.Clock clock;
    public QueueController(QueueState state,QueueMetrics metrics,@Value("${internal.secret}") String secret,java.time.Clock clock) {
        this.state=state;this.metrics=metrics;this.clock=clock;this.secret=secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    public record Enter(String userId) {}
    public record Leave(UUID token) {}
    public record SlotEvent(QueueState.Event type,Instant at) {}
    public record SaleState(boolean soldOut,Instant releaseAt) {}
    @PostMapping("/queue/enter") Map<String,Object> enter(@RequestBody Enter request) { return state.enter(request.userId()); }
    @GetMapping("/queue/status") Map<String,Object> status(@RequestParam UUID token) { return state.status(token); }
    @PostMapping("/queue/leave") Map<String,Boolean> leave(@RequestBody Leave request) {
        if(request.token()==null) throw new IllegalArgumentException("Missing token");state.leave(request.token());return Map.of("left",true);
    }
    @PostMapping("/internal/slots/{kid}/events") Map<String,Boolean> event(@PathVariable UUID kid,@RequestBody SlotEvent event,
            @RequestHeader(value="X-Internal-Secret",required=false) String supplied,@RequestHeader(value="X-Run-Epoch",required=false) String epoch) {
        authenticate(supplied);if(event.type()==null) throw new IllegalArgumentException("Missing type");return Map.of("ignored",state.slotEvent(kid,event.type(),event.at(),epoch));
    }
    @PostMapping("/internal/sale-state") Map<String,Boolean> sale(@RequestBody SaleState sale,
            @RequestHeader(value="X-Internal-Secret",required=false) String supplied,@RequestHeader(value="X-Run-Epoch",required=false) String epoch) {
        authenticate(supplied);return Map.of("ignored",state.saleState(sale.soldOut(),epoch));
    }
    @PostMapping("/admin/reset") Map<String,Object> reset(@RequestBody QueueConfig config) {
        long received=System.nanoTime(); // 3장: 기준점은 처리 전에 잡는다
        if(config.anchorAt()!=null && clock instanceof AnchoredClock anchored) anchored.anchor(config.anchorAt(),received);
        state.reset(config);metrics.reset();return Map.of("config",config); }
    @GetMapping("/admin/stats") Map<String,Object> stats() { return state.stats(); }
    @GetMapping("/admin/metrics") Map<String,Object> metrics() { return metrics.snapshot(); }
    @GetMapping("/admin/snapshot") Map<String,Object> snapshot() { return state.snapshot(); }
    private void authenticate(String supplied) {
        if(supplied==null || !java.security.MessageDigest.isEqual(secret,supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw new InternalUnauthorized();
    }
    static class InternalUnauthorized extends RuntimeException {}
    @RestControllerAdvice
    public static class Errors {
        @ExceptionHandler(InternalUnauthorized.class) ResponseEntity<?> auth() { return ResponseEntity.status(401).body(Map.of("code","UNAUTHORIZED","message","Invalid internal secret")); }
        @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("code","VALIDATION_FAILED","message",Objects.toString(error.getMessage(),"Invalid request"))); }
    }
}
