package com.example.library.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.example.library.identity.domain.exception.InvalidCredentialsException;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.PasswordHash;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.User;
import com.example.library.identity.domain.model.UserId;
import org.junit.jupiter.api.Test;

/** RM-AUTH-04 et RM-AUTH-07 : tous les échecs lèvent la même exception, avec le même code et le même message. */
class LoginPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private final LoginPolicy policy = new LoginPolicy();

    private static User user(boolean enabled) {
        return User.restore(UserId.newId(), Email.of("alice@example.org"), new PasswordHash("$2a$10$x"),
                enabled, Set.of(Role.MEMBER), NOW);
    }

    @Test
    void authenticate_quandTousCriteresReunis_alorsRenvoieLUtilisateur() {
        // Given
        User user = user(true);

        // When
        User result = policy.authenticate(Optional.of(user), "S3cret!pass-long", true);

        // Then
        assertThat(result).isSameAs(user);
    }

    @Test
    void authenticate_quandQuatreCasDEchec_alorsMemeExceptionMemeCodeMemeMessage() {
        // Given
        List<InvalidCredentialsException> failures = new ArrayList<>();
        List<Runnable> cases = List.of(
                () -> policy.authenticate(Optional.empty(), "S3cret!pass-long", false),           // e-mail inconnu
                () -> policy.authenticate(Optional.of(user(true)), "mauvais", false),             // mauvais mot de passe
                () -> policy.authenticate(Optional.of(user(false)), "S3cret!pass-long", true),    // désactivé
                () -> policy.authenticate(Optional.of(user(true)), "a".repeat(73), true));        // > 72 octets

        // When
        for (Runnable testCase : cases) {
            try {
                testCase.run();
            } catch (InvalidCredentialsException e) {
                failures.add(e);
            }
        }

        // Then
        assertThat(failures).hasSize(4);
        assertThat(failures).extracting(InvalidCredentialsException::getCode).containsOnly("INVALID_CREDENTIALS");
        assertThat(failures).extracting(Throwable::getMessage).containsOnly("E-mail ou mot de passe incorrect");
    }

    @Test
    void authenticate_quandMotDePasseVide_alorsInvalidCredentials() {
        // When / Then
        assertThatThrownBy(() -> policy.authenticate(Optional.of(user(true)), "", true))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void authenticate_quandMotDePasseNull_alorsInvalidCredentials() {
        // When / Then
        assertThatThrownBy(() -> policy.authenticate(Optional.of(user(true)), null, true))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
