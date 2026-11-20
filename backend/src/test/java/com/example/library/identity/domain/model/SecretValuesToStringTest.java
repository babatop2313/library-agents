package com.example.library.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** RM-AUTH-03 : ni le hash ni le jeton ne doivent apparaître dans un toString. */
class SecretValuesToStringTest {

    @Test
    void toString_quandPasswordHash_alorsValeurMasquee() {
        // Given
        PasswordHash hash = new PasswordHash("$2a$10$secrethashvalue");

        // When / Then
        assertThat(hash.toString()).doesNotContain("secrethashvalue");
    }

    @Test
    void toString_quandAccessToken_alorsJetonMasque() {
        // Given
        AccessToken token = new AccessToken("eyJhbGciOi.secret.jeton", Instant.parse("2026-11-20T11:00:00Z"));

        // When / Then
        assertThat(token.toString()).doesNotContain("eyJhbGciOi").doesNotContain("jeton");
    }

    @Test
    void constructeur_quandHashVideOuNull_alorsRefuse() {
        // When / Then
        assertThatThrownBy(() -> new PasswordHash("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordHash(null)).isInstanceOf(NullPointerException.class);
    }
}
