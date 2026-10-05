package dev.endnjs.reservation.seat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;
import dev.endnjs.reservation.config.GradeConfig;
import dev.endnjs.reservation.config.RuntimeConfigStore;
import dev.endnjs.reservation.admission.AdmissionAccess;
import dev.endnjs.reservation.metrics.MetricsSnapshot.Phase;
import dev.endnjs.reservation.sale.SaleService;
import dev.endnjs.reservation.snapshot.StateSnapshotReader;
import org.springframework.web.bind.annotation.*;

@RestController
public class SeatController {
    private final StateSnapshotReader snapshots;
    private final RuntimeConfigStore configs;
    private final AdmissionAccess queue;
    private final SaleService sale;
    private final Clock clock;
    private final SeatsGate gate;
    public SeatController(StateSnapshotReader snapshots, RuntimeConfigStore configs, AdmissionAccess queue, SaleService sale, Clock clock, SeatsGate gate) {
        this.snapshots = snapshots; this.configs = configs; this.queue = queue; this.sale = sale; this.clock = clock; this.gate = gate;
    }
    /** v0.5 4.5: closed after the shared saleEndAt; before it, the v0.4 5.4 summary travels with the map. 순서: 입장키 → 새로고침 제한 → 캐시 → DB. */
    @GetMapping("/seats")
    ResponseEntity<?> seats(@RequestHeader(value = "X-Admission-Key", required = false) String token) {
        var config = configs.current();
        var limited = gate.admit(queue.keyId(token), config); // 입장키 검사는 메모리에서만 (만료·회수·서명)
        if (limited != null) {
            return ResponseEntity.status(429).header("Retry-After", Long.toString((limited.retryAfterMs() + 999) / 1000))
                    .body(Map.of("code", ErrorCode.RATE_LIMITED.name(), "message", "Seat map refreshed too often", "retryAfterMs", limited.retryAfterMs()));
        }
        if (sale.ended()) throw new ApiException(ErrorCode.SALE_ENDED, "Sale has ended");
        return ResponseEntity.ok(gate.read(config, sale.runEpoch(), () -> read(token, config)));
    }
    private SeatResponse read(String token, dev.endnjs.reservation.config.RuntimeConfig config) {
        queue.check(token, null);
        if (sale.ended()) throw new ApiException(ErrorCode.SALE_ENDED, "Sale has ended");
        var snapshot=snapshots.seats();
        var all=snapshot.seats();
        var summary=snapshot.summary();
        return new SeatResponse(config.rows(), config.cols(), summary.soldOut(), config.grades(),
                all.stream().map(s -> new SeatView(s.id(), s.label(), s.grade(), s.status())).toList(),
                summary.availableSeats(), summary.heldSeats(), summary.pendingDepositSeats(), summary.returnPendingSeats(),
                summary.releaseAt(), summary.saleEndAt(), summary.phase(clock.instant()));
    }
    public record SeatResponse(int rows, int cols, boolean soldOut, List<GradeConfig> grades, List<SeatView> seats,
            long availableSeats, long heldSeats, long pendingDepositSeats, long returnPendingSeats,
            Instant releaseAt, Instant saleEndAt, Phase phase) {}
    public record SeatView(long id, String label, Grade grade, SeatStatus status) {}
}
