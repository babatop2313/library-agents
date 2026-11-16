package com.example.library;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis jetable pour les tests d'intégration (nécessite Docker).
 * @ServiceConnection(name = "redis") branche Spring Data Redis sur ce conteneur générique.
 * À importer dans un test : @Import(RedisTestcontainersConfiguration.class)
 * (les tests qui n'utilisent pas Redis, comme les slices JPA, ne l'importent pas : RC-19).
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfiguration {

    public static final String REDIS_IMAGE = "redis:7-alpine";

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);
    }

    /** Aide de test : vide Redis via redis-cli dans le conteneur (aucune dépendance Spring Data Redis). */
    @Bean
    RedisTestCache redisTestCache(@Qualifier("redisContainer") GenericContainer<?> redisContainer) {
        return new RedisTestCache(redisContainer);
    }

    /** Vidage de Redis entre deux tests qui partagent le conteneur. */
    public static final class RedisTestCache {

        private final GenericContainer<?> container;

        RedisTestCache(GenericContainer<?> container) {
            this.container = container;
        }

        public void flushAll() {
            try {
                var result = container.execInContainer("redis-cli", "FLUSHALL");
                if (result.getExitCode() != 0) {
                    throw new IllegalStateException("FLUSHALL a échoué : " + result.getStderr());
                }
            } catch (IOException e) {
                throw new IllegalStateException("FLUSHALL impossible", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("FLUSHALL interrompu", e);
            }
        }
    }
}
