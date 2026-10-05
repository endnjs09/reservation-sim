package dev.endnjs.reservation.admission;

import dev.endnjs.reservation.common.*;
import dev.endnjs.reservation.sale.SaleService;
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
import tools.jackson.databind.json.JsonMapper;

@Component
public class AdmissionKeyChecker {
    public record Claims(UUID kid,String userId,long expiresAt) {}
    private final String secret;
    private final SaleService sale;
    private final Clock clock;
    private final KeyRegistry registry;
    private final JdbcTemplate jdbc;
    private final boolean required;
    public AdmissionKeyChecker(@Value("${admission.key-secret:dev-only-change-me}") String secret,
            @Value("${admission.required:true}") boolean required,SaleService sale,Clock clock,KeyRegistry registry,JdbcTemplate jdbc) {
        this.secret=secret;this.required=required;this.sale=sale;this.clock=clock;this.registry=registry;this.jdbc=jdbc;
    }
    public boolean required() { return required; }
    public Claims check(String key,ProtectedRequest request) {
        Claims claims;String epoch;
        try {
            var parts=key.split("\\.",-1);
            if(parts.length!=3 || !parts[0].equals("v1") || !parts[1].matches("[A-Za-z0-9_-]+") || !parts[2].matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            if(!MessageDigest.isEqual(mac.doFinal(("v1."+parts[1]).getBytes(StandardCharsets.UTF_8)),Base64.getUrlDecoder().decode(parts[2]))) throw new IllegalArgumentException();
            var json=JsonMapper.builder().build().readTree(Base64.getUrlDecoder().decode(parts[1]));
            if(!json.path("exp").isIntegralNumber() || !json.path("iat").isIntegralNumber()) throw new IllegalArgumentException();
            claims=new Claims(UUID.fromString(json.path("kid").asString()),json.path("uid").asString(),json.path("exp").asLong());
            epoch=json.path("run").asString();
            if(claims.userId()==null || claims.userId().isBlank()) throw new IllegalArgumentException();
        } catch(Exception invalid) { throw new ApiException(ErrorCode.KEY_INVALID,"Invalid admission key"); }
        if(!sale.runEpoch().equals(epoch) || request.userId()!=null && !claims.userId().equals(request.userId())) throw new ApiException(ErrorCode.KEY_INVALID,"Admission key does not match run or user");
        if(registry.revoked(claims.kid())) throw new ApiException(ErrorCode.KEY_REVOKED,"Admission key has been revoked");
        if(clock.millis()>=claims.expiresAt() && !request.ongoing(jdbc,claims.userId())) throw new ApiException(ErrorCode.KEY_EXPIRED,"Admission key has expired");
        return claims;
    }
}
