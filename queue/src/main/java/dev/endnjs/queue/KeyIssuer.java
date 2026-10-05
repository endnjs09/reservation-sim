package dev.endnjs.queue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

public final class KeyIssuer {
    private final byte[] secret;
    private final JsonMapper json=JsonMapper.builder().build();
    public KeyIssuer(String secret) {
        if(secret==null || secret.isBlank()) throw new IllegalArgumentException("Missing admission secret");
        this.secret=secret.getBytes(StandardCharsets.UTF_8);
    }
    public String issue(UUID kid,String user,Instant at,Instant expires,String epoch) {
        var encoder=Base64.getUrlEncoder().withoutPadding();
        String payload=encoder.encodeToString(json.writeValueAsBytes(Map.of("kid",kid.toString(),"uid",user,"iat",at.toEpochMilli(),"exp",expires.toEpochMilli(),"run",epoch)));
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return "v1."+payload+"."+encoder.encodeToString(mac.doFinal(("v1."+payload).getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
}
