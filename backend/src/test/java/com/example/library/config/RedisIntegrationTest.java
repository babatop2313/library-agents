package com.example.library.config;

import java.time.Duration;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration;
import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie le branchement de Redis : le contexte démarre avec Spring Data Redis,
 * la connexion vers le conteneur Testcontainers répond et l'indicateur de santé Actuator est UP.
 */
@SpringBootTest
@Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
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

    /** D7 / D11 : REJECT_COMMANDS est appliqué sans écraser le connect-timeout de 2 s de Spring Boot. */
    @Test
    void clientOptions_quandContexteDemarre_alorsRejectCommandsEtConnectTimeoutConserve() {
        // Given : la fabrique de connexions Lettuce construite par l'auto-configuration
        LettuceConnectionFactory factory = (LettuceConnectionFactory) redisConnectionFactory;
        // When
        ClientOptions options = factory.getClientConfiguration().getClientOptions().orElseThrow();
        // Then
        assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
    }
}
