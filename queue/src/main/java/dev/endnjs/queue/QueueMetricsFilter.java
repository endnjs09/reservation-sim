package dev.endnjs.queue;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Integer.MIN_VALUE)
public class QueueMetricsFilter extends OncePerRequestFilter {
    private final QueueMetrics metrics;
    public QueueMetricsFilter(QueueMetrics metrics) { this.metrics=metrics; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String endpoint=switch(request.getRequestURI()) { case "/queue/enter" -> "queue.enter";case "/queue/status" -> "queue.status";case "/queue/leave" -> "queue.leave";default -> request.getRequestURI().startsWith("/internal/slots/") ? "internal.events" : null; };
        if(endpoint==null) { chain.doFilter(request,response);return; }
        var ticket=metrics.start(endpoint);try { chain.doFilter(request,response); } finally { ticket.finish(response.getStatus(),null); }
    }
}
