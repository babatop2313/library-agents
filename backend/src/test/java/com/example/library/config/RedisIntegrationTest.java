package com.example.library.config;

import com.example.library.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie le branchement de Redis : le contexte démarre avec Spring Data Redis,
 * la connexion vers le conteneur Testcontainers répond et l'indicateur de santé Actuator est UP.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisIntegrationTest {

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Test
    void ping_quandRedisEstDemarre_alorsRepondPong() {
        // Given : un Redis jetable fourni par Testcontainers
        // When
        String reply;
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            reply = connection.ping();
        }
        // Then
        assertThat(reply).isEqualTo("PONG");
    }

    @Test
    void healthForPath_quandRedisEstDisponible_alorsStatutUp() {
        // Given : l'indicateur de santé "redis" apporté par le starter
        // When
        HealthDescriptor health = healthEndpoint.healthForPath("redis");
        // Then
        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }
}
