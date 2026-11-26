package com.example.library.config;

import java.time.Clock;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Horloge de l'application : toute date courante passe par ce Clock injecté
 * (remplaçable par Clock.fixed(...) dans les tests). Son fuseau est celui de la bibliothèque (app.timezone).
 */
@Configuration
public class ClockConfig {

    private static final Logger log = LoggerFactory.getLogger(ClockConfig.class);

    @Bean
    Clock clock(@Value("${app.timezone:}") String rawTimeZone) {
        ZoneId zone = LibraryTimeZone.resolve(rawTimeZone);
        log.info("Fuseau de la bibliothèque : {}", zone);
        return Clock.system(zone);
    }
}
