package com.example.library.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.library.identity.domain.model.DevMemberBootstrapOutcome;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.PasswordHash;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** RM-LOAN-30 : table de décision de l'amorçage du membre de test (profil local). Java pur. */
class DevMemberBootstrapPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final String PASSWORD = "S3cret!pass-long";
    private final DevMemberBootstrapPolicy policy = new DevMemberBootstrapPolicy();

    private static User existing(String email, Role... roles) {
        return User.register(Email.of(email), new PasswordHash("$2a$10$x"), Set.of(roles), NOW);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void decide_quandEmailAbsentOuVide_alorsMissingConfiguration(String email) {
        // When / Then
        assertThat(policy.decide(email, PASSWORD, e -> Optional.empty()))
                .isEqualTo(DevMemberBootstrapOutcome.MISSING_CONFIGURATION);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void decide_quandMotDePasseAbsentOuVide_alorsMissingConfiguration(String password) {
        // When / Then
        assertThat(policy.decide("membre@example.org", password, e -> Optional.empty()))
                .isEqualTo(DevMemberBootstrapOutcome.MISSING_CONFIGURATION);
    }

    @Test
    void decide_quandLesDeuxVariablesAbsentes_alorsMissingConfiguration() {
        // When / Then
        assertThat(policy.decide(null, null, e -> Optional.empty()))
                .isEqualTo(DevMemberBootstrapOutcome.MISSING_CONFIGURATION);
    }

    @Test
    void decide_quandEmailMalForme_alorsInvalidEmail() {
        // When / Then
        assertThat(policy.decide("pas-un-email", PASSWORD, e -> Optional.empty()))
                .isEqualTo(DevMemberBootstrapOutcome.INVALID_EMAIL);
    }

    @Test
    void decide_quandMotDePasseDeOnzeCaracteres_alorsWeakPassword() {
        // When / Then : 12 caractères minimum (RM-AUTH-03)
        assertThat(policy.decide("membre@example.org", "12345678901", e -> Optional.empty()))
                .isEqualTo(DevMemberBootstrapOutcome.WEAK_PASSWORD);
    }

    @Test
    void decide_quandMotDePasseAuDela72Octets_alorsWeakPassword() {
        // When / Then : 72 octets maximum (limite de BCrypt)
        assertThat(policy.decide("membre@example.org", "a".repeat(73), e -> Optional.empty()))
                .isEqualTo(DevMemberBootstrapOutcome.WEAK_PASSWORD);
    }

    @Test
    void decide_quandMotDePasseDeDouzeCaracteres_alorsCreationAutorisee() {
        // When / Then
        assertThat(policy.decide("membre@example.org", "123456789012", e -> Optional.empty()))
                .isEqualTo(DevMemberBootstrapOutcome.CREATED);
    }

    @Test
    void decide_quandUtilisateurAvecRoleMemberExistant_alorsAlreadyPresent() {
        // Given
        User member = existing("membre@example.org", Role.MEMBER);
        // When / Then : idempotent
        assertThat(policy.decide("membre@example.org", PASSWORD, e -> Optional.of(member)))
                .isEqualTo(DevMemberBootstrapOutcome.ALREADY_PRESENT);
    }

    @Test
    void decide_quandUtilisateurExistantSansRoleMember_alorsEmailTakenWithoutMemberRole() {
        // Given
        User librarian = existing("membre@example.org", Role.LIBRARIAN);
        // When / Then
        assertThat(policy.decide("membre@example.org", PASSWORD, e -> Optional.of(librarian)))
                .isEqualTo(DevMemberBootstrapOutcome.EMAIL_TAKEN_WITHOUT_MEMBER_ROLE);
    }

    @Test
    void decide_quandAucunUtilisateurDeCetEmail_alorsCreated() {
        // When / Then
        assertThat(policy.decide("membre@example.org", PASSWORD, e -> Optional.empty()))
                .isEqualTo(DevMemberBootstrapOutcome.CREATED);
    }

    @Test
    void decide_quandEmailAvecMajusculesEtEspaces_alorsRechercheSurLEmailNormalise() {
        // Given
        AtomicBoolean searched = new AtomicBoolean();
        // When
        policy.decide("  Membre@Example.ORG ", PASSWORD, e -> {
            searched.set(true);
            assertThat(e.value()).isEqualTo("membre@example.org");
            return Optional.empty();
        });
        // Then
        assertThat(searched).isTrue();
    }

    @Test
    void decide_quandConfigurationRefusee_alorsRechercheEnBaseNonAppelee() {
        // Given
        AtomicBoolean searched = new AtomicBoolean();
        // When : configuration absente, e-mail invalide, mot de passe faible
        policy.decide(null, PASSWORD, e -> { searched.set(true); return Optional.empty(); });
        policy.decide("pas-un-email", PASSWORD, e -> { searched.set(true); return Optional.empty(); });
        policy.decide("membre@example.org", "court", e -> { searched.set(true); return Optional.empty(); });
        // Then
        assertThat(searched).isFalse();
    }

    @Test
    void createMember_quandCreation_alorsRoleMemberSeulActifEtEmailNormalise() {
        // Given
        PasswordHash hash = new PasswordHash("$2a$10$hash");
        // When
        User member = policy.createMember(" Membre@Example.org ", hash, NOW);
        // Then
        assertThat(member.roles()).containsExactly(Role.MEMBER);
        assertThat(member.enabled()).isTrue();
        assertThat(member.email().value()).isEqualTo("membre@example.org");
        assertThat(member.passwordHash()).isEqualTo(hash);
        assertThat(member.createdAt()).isEqualTo(NOW);
    }
}
