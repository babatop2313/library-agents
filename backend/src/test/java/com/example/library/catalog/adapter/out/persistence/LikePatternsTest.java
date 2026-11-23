package com.example.library.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** RM-CAT-17 : les caractères %, _ et \ sont littéraux dans un motif LIKE (ESCAPE '\'). */
class LikePatternsTest {

    @Test
    void containsPattern_quandTexteSimple_alorsEntoureDePourcent() {
        // Given / When / Then
        assertThat(LikePatterns.containsPattern("camus")).isEqualTo("%camus%");
    }

    @Test
    void containsPattern_quandPourcent_alorsEchappe() {
        // Given / When / Then
        assertThat(LikePatterns.containsPattern("100%")).isEqualTo("%100\\%%");
    }

    @Test
    void containsPattern_quandSoulignement_alorsEchappe() {
        // Given / When / Then
        assertThat(LikePatterns.containsPattern("a_b")).isEqualTo("%a\\_b%");
    }

    @Test
    void containsPattern_quandAntislash_alorsDouble() {
        // Given / When / Then
        assertThat(LikePatterns.containsPattern("a\\b")).isEqualTo("%a\\\\b%");
    }

    @Test
    void containsPattern_quandAntislashSuiviDeJoker_alorsAntislashEchappeEnPremier() {
        // Given / When / Then : "\%" -> "\\" puis "\%" (et non "\\%" qui redeviendrait un joker)
        assertThat(LikePatterns.containsPattern("\\%")).isEqualTo("%\\\\\\%%");
    }

    @Test
    void containsPattern_quandTousLesSpeciaux_alorsTousEchappes() {
        // Given / When / Then
        assertThat(LikePatterns.containsPattern("%_\\")).isEqualTo("%\\%\\_\\\\%");
    }

    @Test
    void containsPattern_quandTexteSansSpecial_alorsInchange() {
        // Given / When / Then
        assertThat(LikePatterns.containsPattern("L'Étranger")).isEqualTo("%L'Étranger%");
    }
}
