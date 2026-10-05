package dev.endnjs.reservation.metrics;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class MetricsController {
    private final MetricsService metrics;
    private final MetricsStreamService streams;
    public MetricsController(MetricsService metrics, MetricsStreamService streams) { this.metrics = metrics; this.streams = streams; }
    @GetMapping("/admin/metrics")
    MetricsSnapshot metrics() { return metrics.snapshot(); }
    @GetMapping(value = "/admin/metrics/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() { return streams.subscribe(metrics.snapshot()); }
}
