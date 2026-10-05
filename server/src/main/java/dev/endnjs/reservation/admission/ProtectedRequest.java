package dev.endnjs.reservation.admission;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Routes are resolved before mutation; only the same reservation may use the expiry exception. */
public record ProtectedRequest(String userId, Long reservationId) {
    public static boolean matches(String method,String path) {
        return method.equals("GET") && path.equals("/seats") || method.equals("POST") &&
                (path.equals("/holds") || path.matches("/holds/[0-9]+/(checkout|deposit|release)") || path.equals("/payments/confirm"));
    }
    public static ProtectedRequest read(HttpServletRequest request,byte[] body,JdbcTemplate jdbc) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(path.equals("/seats")) return new ProtectedRequest(null,null);
        try {
            var value=JsonMapper.builder().build().readTree(body);
            String user=value.path("userId").asString(null);
            Long id=null;
            if(path.startsWith("/holds/")) id=Long.valueOf(path.split("/")[2]);
            if(path.equals("/payments/confirm") && value.hasNonNull("orderId")) {
                var ids=jdbc.query("SELECT reservation_id FROM payments WHERE id=?",(rs,row)->rs.getLong(1),UUID.fromString(value.get("orderId").asString()));
                if(!ids.isEmpty()) id=ids.getFirst();
            }
            return new ProtectedRequest(user,id);
        } catch(RuntimeException malformed) { return new ProtectedRequest(null,null); }
    }
    public boolean ongoing(JdbcTemplate jdbc,String uid) {
        return reservationId!=null && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM reservations WHERE id=? AND user_id=? AND status IN ('HELD','CONFIRMING'))",Boolean.class,reservationId,uid));
    }
}
