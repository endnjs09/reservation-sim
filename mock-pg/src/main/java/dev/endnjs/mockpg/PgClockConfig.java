package dev.endnjs.mockpg;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PgClockConfig {
    @Bean Clock clock() { return new AnchoredClock(); }
    @Bean MockPgService.Delay delay() { return Thread::sleep; }
}
