package com.example.library.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.library.identity.domain.model.BootstrapOutcome;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.PasswordHash;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.User;
import org.junit.jupiter.api.Test;

/** RM-AUTH-08 : table de décision de l'amorçage du premier bibliothécaire. */
class LibrarianBootstrapPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final String PASSWORD = "S3cret!pass-long";
    private final LibrarianBootstrapPolicy policy = new LibrarianBootstrapPolicy();

    private static User member(String email) {
        return User.register(Email.of(email), new PasswordHash("$2a$10$x"), Set.of(Role.MEMBER), NOW);
    }

    @Test
    void decide_quandBibliothecaireExistant_alorsAlreadyPresentMemeSiResteInvalide() {
        // When
        BootstrapOutcome outcome = policy.decide(true, null, null, e -> Optional.empty());

        // Then
        assertThat(outcome).isEqualTo(BootstrapOutcome.LIBRARIAN_ALREADY_PRESENT);
    }

    @Test
    void decide_quandEmailAbsentOuVide_alorsMissingConfiguration() {
        // When / Then
        assertThat(policy.decide(false, null, PASSWORD, e -> Optional.empty()))
                .isEqualTo(BootstrapOutcome.MISSING_CONFIGURATION);
        assertThat(policy.decide(false, "  ", PASSWORD, e -> Optional.empty()))
                .isEqualTo(BootstrapOutcome.MISSING_CONFIGURATION);
    }

    @Test
    void decide_quandMotDePasseAbsentOuVide_alorsMissingConfiguration() {
        // When / Then
        assertThat(policy.decide(false, "admin@example.org", null, e -> Optional.empty()))
                .isEqualTo(BootstrapOutcome.MISSING_CONFIGURATION);
        assertThat(policy.decide(false, "admin@example.org", "", e -> Optional.empty()))
                .isEqualTo(BootstrapOutcome.MISSING_CONFIGURATION);
    }

    @Test
    void decide_quandEmailMalForme_alorsInvalidEmail() {
        // When / Then
        assertThat(policy.decide(false, "pas-un-email", PASSWORD, e -> Optional.empty()))
                .isEqualTo(BootstrapOutcome.INVALID_EMAIL);
    }

    @Test
    void decide_quandMotDePasse11Caracteres_alorsWeakPassword() {
        // When / Then
        assertThat(policy.decide(false, "admin@example.org", "a".repeat(11), e -> Optional.empty()))
                .isEqualTo(BootstrapOutcome.WEAK_PASSWORD);
    }

    @Test
    void decide_quandMotDePasse73Octets_alorsWeakPassword() {
        // When / Then
        assertThat(policy.decide(false, "admin@example.org", "a".repeat(73), e -> Optional.empty()))
                .isEqualTo(BootstrapOutcome.WEAK_PASSWORD);
    }

    @Test
    void decide_quandEmailPrisParUnNonBibliothecaire_alorsEmailTaken() {
        // When
        BootstrapOutcome outcome = policy.decide(false, " Admin@Example.org ", PASSWORD,
                email -> Optional.of(member(email.value())));

        // Then
        assertThat(outcome).isEqualTo(BootstrapOutcome.EMAIL_TAKEN_BY_NON_LIBRARIAN);
    }

    @Test
    void decide_quandTousControlesPasses_alorsCreated() {
        // When / Then
        assertThat(policy.decide(false, " Admin@Example.org ", PASSWORD, e -> Optional.empty()))
                .isEqualTo(BootstrapOutcome.CREATED);
    }

    @Test
    void decide_quandEmailRecherche_alorsRechercheAvecLEmailNormalise() {
        // Given
        StringBuilder searched = new StringBuilder();

        // When
        policy.decide(false, " Admin@Example.org ", PASSWORD, email -> {
            searched.append(email.value());
            return Optional.empty();
        });

        // Then
        assertThat(searched).hasToString("admin@example.org");
    }

    @Test
    void decide_quandControleEnAmontEchoue_alorsRechercheParEmailNonAppelee() {
        // Given
        AtomicBoolean called = new AtomicBoolean(false);

        // When
        policy.decide(false, "admin@example.org", "court", e -> {
            called.set(true);
            return Optional.empty();
        });

        // Then
        assertThat(called).isFalse();
    }

    @Test
    void createLibrarian_quandCreation_alorsRoleLibrarianSeulActifEmailNormaliseEtDate() {
        // Given
        PasswordHash hash = new PasswordHash("$2a$10$hash");

        // When
        User librarian = policy.createLibrarian(" Admin@Example.org ", hash, NOW);

        // Then
        assertThat(librarian.roles()).containsExactly(Role.LIBRARIAN);
        assertThat(librarian.enabled()).isTrue();
        assertThat(librarian.email().value()).isEqualTo("admin@example.org");
        assertThat(librarian.createdAt()).isEqualTo(NOW);
        assertThat(librarian.passwordHash()).isEqualTo(hash);
    }
}
