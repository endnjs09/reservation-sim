package dev.endnjs.reservation.payment;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("reservation.pg")
public record PgProperties(String baseUrl, int connectTimeoutMs, int requestTimeoutMs) {
    public PgProperties {
        URI uri = URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || connectTimeoutMs <= 0 || requestTimeoutMs <= 0) {
            throw new IllegalArgumentException("Invalid PG connection settings");
        }
    }
}
