package com.example.library.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration technique du client Redis (Lettuce). */
@Configuration(proxyBeanMethods = false)
public class RedisClientConfig {

    /**
     * D7 : connexion perdue, les commandes sont rejetées immédiatement au lieu d'attendre le timeout.
     * Les ClientOptions de Spring Boot (timeouts) sont conservées, la reconnexion automatique reste active.
     */
    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhenDisconnected() {
        return builder -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
