package dev.endnjs.reservation.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {
    @Bean
    Clock clock() { return new AnchoredClock(); }
    @Bean
    java.util.function.LongSupplier metricsNanoTime() { return System::nanoTime; }
}
