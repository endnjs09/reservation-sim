package dev.endnjs.reservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReservationSimApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReservationSimApplication.class, args);
    }
}
