package dev.endnjs.reservation.admission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import dev.endnjs.reservation.sale.SaleService;
import tools.jackson.databind.json.JsonMapper;

/** Independent cryptographic implementation. Capture before processing, count only successful responses. */
@Component
public class AdmissionKeyAuditor {
    private final byte[] secret;
    private final SaleService sale;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    public AdmissionKeyAuditor(@Value("${admission.key-secret:dev-only-change-me}") String secret,SaleService sale,Clock clock,JdbcTemplate jdbc) {
        this.secret=secret.getBytes(StandardCharsets.UTF_8);this.sale=sale;this.clock=clock;this.jdbc=jdbc;
    }
    public boolean valid(String key,ProtectedRequest request) {
        try {
            int first=key.indexOf('.'),last=key.lastIndexOf('.');
            if(first!=2 || first==last || !key.substring(0,first).equals("v1")) return false;
            String payload=key.substring(first+1,last),signature=key.substring(last+1);
            if(!payload.matches("[A-Za-z0-9_-]+") || !signature.matches("[A-Za-z0-9_-]+")) return false;
            Mac signer=Mac.getInstance("HmacSHA256");signer.init(new SecretKeySpec(secret,"HmacSHA256"));
            if(!MessageDigest.isEqual(signer.doFinal(key.substring(0,last).getBytes(StandardCharsets.UTF_8)),Base64.getUrlDecoder().decode(signature))) return false;
            var body=JsonMapper.builder().build().readTree(Base64.getUrlDecoder().decode(payload));
            UUID.fromString(body.path("kid").asString());
            String uid=body.path("uid").asString(null);
            if(uid==null || uid.isBlank() || !sale.runEpoch().equals(body.path("run").asString(null))) return false;
            if(request.userId()!=null && !request.userId().equals(uid)) return false;
            return body.path("iat").isIntegralNumber() && body.path("exp").isIntegralNumber() &&
                    (clock.millis()<body.path("exp").asLong() || request.ongoing(jdbc,uid));
        } catch(Exception invalid) { return false; }
    }
}
