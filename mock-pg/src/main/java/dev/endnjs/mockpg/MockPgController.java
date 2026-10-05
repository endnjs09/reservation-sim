package dev.endnjs.mockpg;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class MockPgController {
    private final MockPgService pg;
    private final java.time.Clock clock;
    public MockPgController(MockPgService pg, java.time.Clock clock) { this.pg = pg; this.clock = clock; }
    /** 3장: reset 본문은 선택. anchorAt이 있으면 Clock 기준을 새로 잡는다. */
    record ResetRequest(java.time.Instant anchorAt) {}
    @PostMapping("/auth")
    Map<String, String> auth(@RequestBody AuthRequest request) {
        return Map.of("paymentKey", pg.auth(request.orderId(), request.amount()));
    }
    @PostMapping("/payments/confirm")
    MockPgService.Approval confirm(@RequestBody ConfirmRequest request) {
        return pg.confirm(request.paymentKey(), request.orderId(), request.amount());
    }
    @GetMapping("/payments/{key}")
    Map<String, MockPgService.Status> status(@PathVariable String key) { return Map.of("status", pg.status(key)); }
    @PostMapping("/payments/{key}/cancel")
    Map<String, MockPgService.Status> cancel(@PathVariable String key) { return Map.of("status", pg.cancel(key)); }
    @GetMapping("/admin/config")
    PgConfig config() { return pg.config(); }
    @PutMapping("/admin/config")
    PgConfig configure(@RequestBody PgConfig config) { return pg.configure(config); }
    @PostMapping("/admin/reset")
    Map<String, Boolean> reset(@RequestBody(required = false) ResetRequest request) {
        long received = System.nanoTime(); // 기준점은 처리 전에 잡는다
        if (request != null && request.anchorAt() != null && clock instanceof AnchoredClock anchored) anchored.anchor(request.anchorAt(), received);
        pg.reset(); return Map.of("reset", true);
    }
    @GetMapping("/admin/stats")
    MockPgService.Stats stats() { return pg.stats(); }
    @GetMapping("/admin/payments")
    List<MockPgService.PaymentView> payments() { return pg.payments(); }
    public record AuthRequest(UUID orderId, Integer amount) {}
    public record ConfirmRequest(String paymentKey, UUID orderId, Integer amount) {}
}
