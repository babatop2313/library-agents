package com.example.library.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.library.catalog.domain.exception.LimitOutOfRangeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** RM-MCP-07 : limite de la liste des livres disponibles (1 a 20, defaut 10). */
class AvailableBooksLimitTest {

    @Test
    void of_quandAbsente_alorsValeurParDefautDix() {
        // Given / When
        AvailableBooksLimit limit = AvailableBooksLimit.of(null);
        // Then
        assertThat(limit.value()).isEqualTo(10);
        assertThat(AvailableBooksLimit.DEFAULT).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 19, 20})
    void of_quandDansLesBornes_alorsAcceptee(int requested) {
        // When
        AvailableBooksLimit limit = AvailableBooksLimit.of(requested);
        // Then
        assertThat(limit.value()).isEqualTo(requested);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 21, 1000, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void of_quandHorsBornes_alorsLimitOutOfRange(int requested) {
        // When / Then
        assertThatThrownBy(() -> AvailableBooksLimit.of(requested))
                .isInstanceOf(LimitOutOfRangeException.class)
                .extracting("code").isEqualTo("LIMIT_OUT_OF_RANGE");
    }

    @Test
    void constructeur_quandHorsBornes_alorsExceptionPorteValeurEtBornes() {
        // When / Then
        assertThatThrownBy(() -> new AvailableBooksLimit(21))
                .isInstanceOfSatisfying(LimitOutOfRangeException.class, e -> {
                    assertThat(e.getRequested()).isEqualTo(21);
                    assertThat(e.getMin()).isEqualTo(1);
                    assertThat(e.getMax()).isEqualTo(20);
                });
    }

    @Test
    void bornes_quandConsultees_alorsUnAVingt() {
        // When / Then
        assertThat(AvailableBooksLimit.MIN).isEqualTo(1);
        assertThat(AvailableBooksLimit.MAX).isEqualTo(20);
    }
}
