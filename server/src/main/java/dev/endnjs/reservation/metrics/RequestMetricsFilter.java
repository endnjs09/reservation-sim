package dev.endnjs.reservation.metrics;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class RequestMetricsFilter extends OncePerRequestFilter {
    private final MetricsCollector metrics;
    private final dev.endnjs.reservation.admission.AdmissionKeyChecker checker;
    private final dev.endnjs.reservation.admission.AdmissionKeyAuditor auditor;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public RequestMetricsFilter(MetricsCollector metrics,dev.endnjs.reservation.admission.AdmissionKeyChecker checker,
            dev.endnjs.reservation.admission.AdmissionKeyAuditor auditor,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.metrics=metrics;this.checker=checker;this.auditor=auditor;this.jdbc=jdbc;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String endpoint = endpoint(request.getMethod(), request.getRequestURI().substring(request.getContextPath().length()));
        if (endpoint == null) { chain.doFilter(request, response); return; }
        var measurement = metrics.requestStarted(endpoint);
        boolean audited=false,valid=true;
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(checker.required() && dev.endnjs.reservation.admission.ProtectedRequest.matches(request.getMethod(),path)) {
            var replay=new dev.endnjs.reservation.admission.ReplayableRequest(request);
            var target=dev.endnjs.reservation.admission.ProtectedRequest.read(replay,replay.body(),jdbc);
            replay.setAttribute(dev.endnjs.reservation.admission.ProtectedRequest.class.getName(),target);
            valid=auditor.valid(replay.getHeader("X-Admission-Key"),target);audited=true;request=replay;
        }
        var capture=new org.springframework.web.util.ContentCachingResponseWrapper(response);
        boolean failed = true;
        try { chain.doFilter(request, capture); failed = false; }
        finally {
            if(audited && !valid && !failed && capture.getStatus()>=200 && capture.getStatus()<300) measurement.acceptedInvalidKey();
            String code=null;
            if(capture.getStatus()>=400) {
                var match=java.util.regex.Pattern.compile("\"code\"\s*:\s*\"([^\"]+)\"").matcher(new String(capture.getContentAsByteArray(),java.nio.charset.StandardCharsets.UTF_8));
                if(match.find()) code=match.group(1);
            }
            try { capture.copyBodyToResponse(); }
            finally { measurement.finish(failed ? 500 : capture.getStatus(),code); }
        }
    }
    private static String endpoint(String method, String path) {
        if (method.equals("GET")) {
            if (path.equals("/seats")) return "seats";
            if (path.matches("/reservations/[0-9]+")) return "reservation";
        }
        if (method.equals("POST")) {
            if (path.equals("/holds")) return "holds";
            if (path.equals("/payments/confirm")) return "confirm";
            if (path.matches("/holds/[0-9]+/checkout")) return "checkout";
            if (path.matches("/holds/[0-9]+/deposit")) return "deposit";
            if (path.matches("/deposits/[0-9]+/pay")) return "deposit.pay";
            if (path.matches("/reservations/[0-9]+/cancel")) return "reservation.cancel";
            if (path.matches("/holds/[0-9]+/release")) return "release";
        }
        return null;
    }
}
