package com.example.library.loan.support;

import java.time.Clock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Remplace l'horloge de l'application par une {@link MutableClock} (prioritaire sur celle de {@code ClockConfig}). */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockTestConfiguration {

    @Bean
    MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    Clock testClock(MutableClock mutableClock) {
        return mutableClock;
    }
}
