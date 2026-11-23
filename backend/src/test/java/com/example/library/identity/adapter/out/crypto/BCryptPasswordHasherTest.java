package com.example.library.identity.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.library.identity.domain.model.PasswordHash;
import org.junit.jupiter.api.Test;

/** RM-AUTH-03 et RM-AUTH-07 : BCrypt, hash factice et entrées non comparables (coût 4 pour la rapidité). */
class BCryptPasswordHasherTest {

    private static final String PASSWORD = "S3cret!pass-long";
    private final BCryptPasswordHasher hasher = new BCryptPasswordHasher(4);

    @Test
    void hash_quandMotDePasse_alorsFormatBcryptSansTexteEnClair() {
        // When
        PasswordHash hash = hasher.hash(PASSWORD);

        // Then
        assertThat(hash.value()).startsWith("$2").hasSize(60).doesNotContain(PASSWORD);
    }

    @Test
    void hash_quandMemeMotDePasseDeuxFois_alorsSelDifferent() {
        // When / Then
        assertThat(hasher.hash(PASSWORD)).isNotEqualTo(hasher.hash(PASSWORD));
    }

    @Test
    void matches_quandBonMotDePasse_alorsVrai() {
        // Given
        PasswordHash hash = hasher.hash(PASSWORD);

        // When / Then
        assertThat(hasher.matches(PASSWORD, hash)).isTrue();
    }

    @Test
    void matches_quandMauvaisMotDePasse_alorsFaux() {
        // Given
        PasswordHash hash = hasher.hash(PASSWORD);

        // When / Then
        assertThat(hasher.matches("autre-mot-de-passe", hash)).isFalse();
    }

    @Test
    void matches_quandPlusDe72Octets_alorsFauxSansException() {
        // Given
        PasswordHash hash = hasher.hash(PASSWORD);

        // When / Then
        assertThat(hasher.matches("a".repeat(73), hash)).isFalse();
    }

    @Test
    void matches_quandNull_alorsFauxSansException() {
        // Given
        PasswordHash hash = hasher.hash(PASSWORD);

        // When / Then
        assertThat(hasher.matches(null, hash)).isFalse();
    }

    @Test
    void matches_quandPlusDe72OctetsMemeSiLes72PremiersCorrespondent_alorsFauxPasDeTroncature() {
        // Given : un hash valide pour 72 octets ; 73 octets (mêmes 72 premiers) ne doit pas passer
        String seventyTwo = "a".repeat(72);
        PasswordHash hash = hasher.hash(seventyTwo);

        // When / Then
        assertThat(hasher.matches(seventyTwo, hash)).isTrue();
        assertThat(hasher.matches(seventyTwo + "b", hash)).isFalse();
    }

    @Test
    void hash_quandPlusDe72Octets_alorsRefuseSansTroncature() {
        // When / Then
        assertThatThrownBy(() -> hasher.hash("a".repeat(73))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dummyHash_quandDemande_alorsHashBcryptValideUtilisableEnComparaison() {
        // When
        PasswordHash dummy = hasher.dummyHash();

        // Then
        assertThat(dummy.value()).startsWith("$2").hasSize(60);
        assertThat(hasher.matches(PASSWORD, dummy)).isFalse();
        assertThat(hasher.dummyHash()).isSameAs(dummy);
    }

    @Test
    void dummyHash_quandMemeCoutQueLesVraisHashs_alorsMemePrefixeDeCout() {
        // When
        String realPrefix = hasher.hash(PASSWORD).value().substring(0, 7);

        // Then
        assertThat(hasher.dummyHash().value()).startsWith(realPrefix);
    }
}
