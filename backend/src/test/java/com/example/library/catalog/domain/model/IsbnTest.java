package com.example.library.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.library.catalog.domain.exception.InvalidIsbnException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** RM-07, RM-CAT-02, RM-CAT-03, RM-CAT-11 : validité et normalisation de l'ISBN. */
class IsbnTest {

    @Test
    void of_quandTreizeChiffres_alorsIsbnCree() {
        // Given
        String raw = "9782070368228";
        // When
        Isbn isbn = Isbn.of(raw);
        // Then
        assertThat(isbn.value()).isEqualTo("9782070368228");
    }

    @Test
    void of_quandTirets_alorsNormalise() {
        // Given
        String raw = "978-2-07-036822-8";
        // When
        Isbn isbn = Isbn.of(raw);
        // Then
        assertThat(isbn.value()).isEqualTo("9782070368228");
    }

    @Test
    void of_quandEspacesAutourEtInterieurs_alorsNormalise() {
        // Given
        String raw = " 978 2070368228 ";
        // When
        Isbn isbn = Isbn.of(raw);
        // Then
        assertThat(isbn.value()).isEqualTo("9782070368228");
    }

    @Test
    void of_quandFormeAvecTiretsEtSansTirets_alorsMemeIsbn() {
        // Given / When
        Isbn withDashes = Isbn.of("978-2-07-036822-8");
        Isbn plain = Isbn.of("9782070368228");
        // Then
        assertThat(withDashes).isEqualTo(plain);
    }

    @Test
    void of_quandChecksumFaux_alorsAccepte() {
        // Given : H1, pas de calcul de cle de controle
        // When
        Isbn isbn = Isbn.of("9782070368229");
        // Then
        assertThat(isbn.value()).isEqualTo("9782070368229");
    }

    @ParameterizedTest
    @ValueSource(strings = {"978207036822", "97820703682280", "978207036822A", "978.2070368228", "   ", "-", "123"})
    @NullAndEmptySource
    void of_quandFormeInvalide_alorsInvalidIsbnException(String raw) {
        // When / Then
        assertThatThrownBy(() -> Isbn.of(raw))
                .isInstanceOf(InvalidIsbnException.class)
                .extracting("code").isEqualTo("ISBN_INVALID");
    }

    @Test
    void constructeur_quandValeurNonNormalisee_alorsInvalidIsbnException() {
        // When / Then : le record refuse lui-meme les separateurs et null
        assertThatThrownBy(() -> new Isbn("978-2-07-036822-8")).isInstanceOf(InvalidIsbnException.class);
        assertThatThrownBy(() -> new Isbn(null)).isInstanceOf(InvalidIsbnException.class);
    }

    @Test
    void isValid_quandValeursVariees_alorsReflecteLaRegle() {
        // Given / When / Then
        assertThat(Isbn.isValid("978-2-07-036822-8")).isTrue();
        assertThat(Isbn.isValid(" 9782070368228 ")).isTrue();
        assertThat(Isbn.isValid("978207036822")).isFalse();
        assertThat(Isbn.isValid("978.2070368228")).isFalse();
        assertThat(Isbn.isValid("")).isFalse();
        assertThat(Isbn.isValid(null)).isFalse();
    }
}
