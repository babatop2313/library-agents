package com.example.library.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** RM-LOAN-29 : résolution du fuseau de la bibliothèque. */
class LibraryTimeZoneTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t"})
    void resolve_quandAbsentVideOuBlanc_alorsAfricaDakar(String raw) {
        // When / Then : une variable laissée vide vaut absence
        assertThat(LibraryTimeZone.resolve(raw)).isEqualTo(ZoneId.of("Africa/Dakar"));
        assertThat(LibraryTimeZone.DEFAULT).isEqualTo(ZoneId.of("Africa/Dakar"));
    }

    @Test
    void resolve_quandFuseauValideAvecEspaces_alorsFuseauApresTrim() {
        // When / Then
        assertThat(LibraryTimeZone.resolve(" Europe/Paris ")).isEqualTo(ZoneId.of("Europe/Paris"));
    }

    @Test
    void resolve_quandFuseauValide_alorsUtiliseTelQuel() {
        // When / Then
        assertThat(LibraryTimeZone.resolve("Asia/Tokyo")).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(LibraryTimeZone.resolve("UTC")).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void resolve_quandIdentifiantInconnu_alorsInvalidTimeZoneQuiNommeLaProprieteEtLaValeur() {
        // When / Then : aucun repli silencieux sur le défaut
        assertThatThrownBy(() -> LibraryTimeZone.resolve("Mars/Olympus"))
                .isInstanceOf(InvalidTimeZoneException.class)
                .hasMessageContaining("app.timezone")
                .hasMessageContaining("Mars/Olympus")
                .satisfies(e -> {
                    InvalidTimeZoneException invalid = (InvalidTimeZoneException) e;
                    assertThat(invalid.property()).isEqualTo("app.timezone");
                    assertThat(invalid.value()).isEqualTo("Mars/Olympus");
                });
    }

    @Test
    void resolve_quandDecalageHorsBornes_alorsInvalidTimeZone() {
        // When / Then
        assertThatThrownBy(() -> LibraryTimeZone.resolve("+25:00"))
                .isInstanceOf(InvalidTimeZoneException.class)
                .hasMessageContaining("app.timezone")
                .hasMessageContaining("+25:00");
    }

    @Test
    void resolve_quandValeurInvalideAvecEspaces_alorsLaValeurFautiveEstCelleSaisie() {
        // When / Then
        assertThatThrownBy(() -> LibraryTimeZone.resolve(" Mars/Olympus "))
                .isInstanceOf(InvalidTimeZoneException.class)
                .hasMessageContaining("Mars/Olympus");
    }
}
