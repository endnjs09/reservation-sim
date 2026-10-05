package dev.endnjs.reservation.admission;

import dev.endnjs.reservation.common.ApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+1)
public class AdmissionKeyFilter extends OncePerRequestFilter {
    static final ThreadLocal<ProtectedRequest> CONTEXT=new ThreadLocal<>();
    private final AdmissionKeyChecker checker;
    public AdmissionKeyFilter(AdmissionKeyChecker checker) { this.checker=checker; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(!checker.required() || !ProtectedRequest.matches(request.getMethod(),path)) { chain.doFilter(request,response);return; }
        var target=(ProtectedRequest)request.getAttribute(ProtectedRequest.class.getName());
        if(target==null) target=new ProtectedRequest(null,null);
        try {
            checker.check(request.getHeader("X-Admission-Key"),target);
            CONTEXT.set(target);chain.doFilter(request,response);
        } catch(ApiException denied) {
            response.setStatus(denied.code().httpStatus());response.setContentType("application/json");
            response.getWriter().write(JsonMapper.builder().build().writeValueAsString(Map.of("code",denied.code().name(),"message",denied.getMessage())));
        } finally { CONTEXT.remove(); }
    }
}
