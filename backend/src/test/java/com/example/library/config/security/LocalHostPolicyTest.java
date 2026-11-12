package com.example.library.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Profil mcp-local : seul un en-tête Host local (localhost ou 127.0.0.1, port optionnel) est accepté. */
class LocalHostPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "localhost:8080", "127.0.0.1", "127.0.0.1:6274"})
    void isAllowed_quandHostLocal_alorsAccepte(String host) {
        // When / Then
        assertThat(LocalHostPolicy.isAllowed(host)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "evil.example", "localhost.evil.com", "127.0.0.1.evil.com", "LOCALHOST", "[::1]", "[::1]:8080",
        "localhost:", "localhost:abc", "0.0.0.0"})
    void isAllowed_quandHostAbsentOuNonLocal_alorsRefuse(String host) {
        // When / Then
        assertThat(LocalHostPolicy.isAllowed(host)).isFalse();
    }

    @Test
    void isAllowed_quandPortTropLong_alorsRefuse() {
        // When / Then : au plus 5 chiffres
        assertThat(LocalHostPolicy.isAllowed("localhost:123456")).isFalse();
    }
}
