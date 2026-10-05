package dev.endnjs.queue;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private final String[] origins;
    public CorsConfig(org.springframework.core.env.Environment environment) {
        this.origins=org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("cors-origins",org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                .orElse(java.util.List.of("http://localhost:8090")).toArray(String[]::new);
    }
    @Override public void addCorsMappings(CorsRegistry registry) { registry.addMapping("/**").allowedOrigins(origins).allowedMethods("GET","POST","OPTIONS").allowedHeaders("*"); }
}
