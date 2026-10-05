package dev.endnjs.reservation.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class AdminCorsConfig implements WebMvcConfigurer {
    @ConfigurationProperties("reservation")
    public record CorsProperties(List<String> corsOrigins) {}
    private final CorsProperties properties;
    public AdminCorsConfig(CorsProperties properties) { this.properties = properties; }
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = properties.corsOrigins();
        if (origins != null && !origins.isEmpty()) {
            registry.addMapping("/admin/**").allowedOrigins(origins.toArray(String[]::new)).allowedMethods("GET");
        }
    }
}
