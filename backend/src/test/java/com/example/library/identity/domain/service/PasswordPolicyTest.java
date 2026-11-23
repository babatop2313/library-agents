package com.example.library.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.library.identity.domain.exception.PasswordPolicyViolationException;
import com.example.library.identity.domain.exception.PasswordPolicyViolationException.Reason;
import org.junit.jupiter.api.Test;

/** RM-AUTH-03 : 12 caractères minimum, 72 octets UTF-8 maximum à la création ; aucun minimum à la connexion. */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void checkNewPassword_quand11Caracteres_alorsTooShort() {
        // When / Then
        assertThatThrownBy(() -> policy.checkNewPassword("a".repeat(11)))
                .isInstanceOfSatisfying(PasswordPolicyViolationException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TOO_SHORT));
    }

    @Test
    void checkNewPassword_quand12Caracteres_alorsAccepte() {
        // When / Then
        assertThatCode(() -> policy.checkNewPassword("a".repeat(12))).doesNotThrowAnyException();
    }

    @Test
    void checkNewPassword_quandNull_alorsTooShort() {
        // When / Then
        assertThatThrownBy(() -> policy.checkNewPassword(null))
                .isInstanceOfSatisfying(PasswordPolicyViolationException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TOO_SHORT));
    }

    @Test
    void checkNewPassword_quand72Octets_alorsAccepte() {
        // When / Then
        assertThatCode(() -> policy.checkNewPassword("a".repeat(72))).doesNotThrowAnyException();
    }

    @Test
    void checkNewPassword_quand73Octets_alorsTooLong() {
        // When / Then
        assertThatThrownBy(() -> policy.checkNewPassword("a".repeat(73)))
                .isInstanceOfSatisfying(PasswordPolicyViolationException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TOO_LONG));
    }

    @Test
    void checkNewPassword_quandMultioctetsDe72Octets_alorsAccepte() {
        // Given : 36 x "é" = 36 caractères, 72 octets
        String password = "é".repeat(36);

        // When / Then
        assertThatCode(() -> policy.checkNewPassword(password)).doesNotThrowAnyException();
    }

    @Test
    void checkNewPassword_quandMultioctetsDe73Octets_alorsTooLong() {
        // Given : 35 x "é" (70 octets) + 3 x "a" = 38 caractères, 73 octets
        String password = "é".repeat(35) + "aaa";

        // When / Then
        assertThatThrownBy(() -> policy.checkNewPassword(password))
                .isInstanceOfSatisfying(PasswordPolicyViolationException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TOO_LONG));
    }

    @Test
    void checkNewPassword_quandPaireSurrogeeComptee_alorsUnSeulCaractere() {
        // Given : 6 émojis (1 point de code, 4 octets chacun) = 6 caractères < 12
        String password = "😀".repeat(6);

        // When / Then
        assertThatThrownBy(() -> policy.checkNewPassword(password))
                .isInstanceOfSatisfying(PasswordPolicyViolationException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TOO_SHORT));
    }

    @Test
    void exception_quandViolation_alorsMessageSansValeurNiLongueur() {
        // Given
        String password = "secretXYZ";

        // When / Then
        assertThatThrownBy(() -> policy.checkNewPassword(password))
                .hasMessageNotContaining("secretXYZ")
                .hasMessageNotContaining("9");
    }

    @Test
    void isVerifiable_quandVideOuNull_alorsFaux() {
        // When / Then
        assertThat(policy.isVerifiable("")).isFalse();
        assertThat(policy.isVerifiable(null)).isFalse();
    }

    @Test
    void isVerifiable_quandCourtMaisNonVide_alorsVraiSansMinimum() {
        // When / Then
        assertThat(policy.isVerifiable("a")).isTrue();
    }

    @Test
    void isVerifiable_quandFrontiere72Et73Octets_alorsVraiPuisFaux() {
        // When / Then
        assertThat(policy.isVerifiable("a".repeat(72))).isTrue();
        assertThat(policy.isVerifiable("a".repeat(73))).isFalse();
        assertThat(policy.isVerifiable("é".repeat(36))).isTrue();
        assertThat(policy.isVerifiable("é".repeat(36) + "a")).isFalse();
    }
}
