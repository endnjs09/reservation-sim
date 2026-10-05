package dev.endnjs.reservation.admin;

import java.util.Map;
import dev.endnjs.reservation.config.ResetRequest;
import dev.endnjs.reservation.config.RuntimeConfig;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final AdminService admin;
    private final StatsService stats;
    public AdminController(AdminService admin, StatsService stats) { this.admin = admin; this.stats = stats; }
    @PostMapping("/reset")
    Map<String, RuntimeConfig> reset(@RequestBody ResetRequest request) {
        long received = System.nanoTime(); // 3장: 기준점은 처리(TRUNCATE 등) 전에 잡는다
        return Map.of("config", admin.reset(request, received));
    }
    @GetMapping("/snapshot")
    Map<String,Object> snapshot() { return stats.snapshot(); }
    @GetMapping("/stats")
    Map<String, Object> stats() { return stats.stats(); }
}
