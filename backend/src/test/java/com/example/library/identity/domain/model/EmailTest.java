package com.example.library.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.library.identity.domain.exception.InvalidEmailException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** RM-AUTH-02 : normalisation et format de l'e-mail. */
class EmailTest {

    @Test
    void of_quandEspacesEtMajuscules_alorsTrimEtMinuscules() {
        // Given
        String raw = "  Alice@Example.ORG  ";

        // When
        Email email = Email.of(raw);

        // Then
        assertThat(email.value()).isEqualTo("alice@example.org");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "pas-un-email", "a@b", "@example.org", "a@@example.org", "a b@example.org",
            "alice@exa mple.org"})
    void of_quandFormatInvalide_alorsInvalidEmailException(String raw) {
        // When / Then
        assertThatThrownBy(() -> Email.of(raw)).isInstanceOf(InvalidEmailException.class);
    }

    @Test
    void of_quandNull_alorsInvalidEmailException() {
        // When / Then
        assertThatThrownBy(() -> Email.of(null)).isInstanceOf(InvalidEmailException.class);
    }

    @Test
    void of_quand254Caracteres_alorsAccepte() {
        // Given : 254 caractères au total
        String raw = "a".repeat(254 - "@example.org".length()) + "@example.org";

        // When
        Email email = Email.of(raw);

        // Then
        assertThat(raw).hasSize(254);
        assertThat(email.value()).hasSize(254);
    }

    @Test
    void of_quand255Caracteres_alorsInvalidEmailException() {
        // Given
        String raw = "a".repeat(255 - "@example.org".length()) + "@example.org";

        // When / Then
        assertThat(raw).hasSize(255);
        assertThatThrownBy(() -> Email.of(raw)).isInstanceOf(InvalidEmailException.class);
    }

    @Test
    void of_quandEspacesAutourDe254Caracteres_alorsAccepteApresTrim() {
        // Given
        String raw = "  " + "a".repeat(254 - "@example.org".length()) + "@example.org  ";

        // When / Then
        assertThat(Email.of(raw).value()).hasSize(254);
    }

    @Test
    void exception_quandEmailInvalide_alorsMessageSansLaValeurSaisieEtCodeStable() {
        // When / Then
        assertThatThrownBy(() -> Email.of("secret-valeur"))
                .isInstanceOfSatisfying(InvalidEmailException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain("secret-valeur");
                    assertThat(e.getCode()).isEqualTo("INVALID_EMAIL");
                });
    }

    @Test
    void hasValidFormat_quandNullOuInvalide_alorsFaux() {
        // When / Then
        assertThat(Email.hasValidFormat(null)).isFalse();
        assertThat(Email.hasValidFormat("pas-un-email")).isFalse();
        assertThat(Email.hasValidFormat("  alice@example.org ")).isTrue();
    }

    @Test
    void isTooLong_quandFrontiere_alorsVraiSeulementAu255() {
        // Given
        String ok = "a".repeat(254);
        String ko = "a".repeat(255);

        // When / Then
        assertThat(Email.isTooLong(null)).isFalse();
        assertThat(Email.isTooLong(ok)).isFalse();
        assertThat(Email.isTooLong(ko)).isTrue();
        assertThat(Email.isTooLong(" " + ok + " ")).isFalse();
    }

    @Test
    void masked_quandEmailValide_alorsPremiereLettreEtDomaine() {
        // When / Then
        assertThat(Email.of("Alice@example.org").masked()).isEqualTo("a***@example.org");
    }
}
