package com.example.library.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Profil mcp-local : seules les origines locales (ou l'absence d'Origin) sont acceptées. */
class LocalOriginPolicyTest {

    @Test
    void isAllowed_quandOriginAbsente_alorsAcceptee() {
        // When / Then
        assertThat(LocalOriginPolicy.isAllowed(null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost", "http://localhost:6274", "http://127.0.0.1", "http://127.0.0.1:8080"})
    void isAllowed_quandOriginLocale_alorsAcceptee(String origin) {
        // When / Then
        assertThat(LocalOriginPolicy.isAllowed(origin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://evil.example", "http://localhost.evil.com", "http://127.0.0.1.evil.com", "null",
        "http://evil.com:80", "http://localhost/", "http://localhost:abc", "", "https://localhost",
        "http://LOCALHOST", "http://[::1]", "http://localhost:"})
    void isAllowed_quandOriginNonLocale_alorsRefusee(String origin) {
        // When / Then
        assertThat(LocalOriginPolicy.isAllowed(origin)).isFalse();
    }
}
