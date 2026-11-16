package com.example.library.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;

/** D7 : configuration du client Lettuce (unitaire, sans Spring). */
class RedisClientConfigTest {

    @Test
    void customize_quandApplique_alorsDisconnectedBehaviorRejectCommands() {
        // Given : un constructeur d'options neuf et le customizer de la configuration
        LettuceClientOptionsBuilderCustomizer customizer = new RedisClientConfig().rejectCommandsWhenDisconnected();
        ClientOptions.Builder builder = ClientOptions.builder();
        assertThat(builder.build().getDisconnectedBehavior())
                .isNotEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        // When
        customizer.customize(builder);
        // Then
        assertThat(builder.build().getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
