package com.example.library.identity.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import com.example.library.identity.application.fake.FakePasswordHasher;
import com.example.library.identity.application.fake.InMemoryUserRepository;
import com.example.library.identity.application.port.in.BootstrapDevMemberCommand;
import com.example.library.identity.application.port.in.DevMemberBootstrapResult;
import com.example.library.identity.domain.model.DevMemberBootstrapOutcome;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.PasswordHash;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.User;
import org.junit.jupiter.api.Test;

/** RM-LOAN-30 : orchestration de l'amorçage du membre de test ; la décision est testée dans le domaine. */
class BootstrapDevMemberServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final String PASSWORD = "S3cret!pass-long";

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final FakePasswordHasher hasher = new FakePasswordHasher();
    private final BootstrapDevMemberService service =
            new BootstrapDevMemberService(users, users, hasher, Clock.fixed(NOW, ZoneOffset.UTC));

    private static User existing(String email, Role role) {
        return User.register(Email.of(email), new PasswordHash("$2a$10$x"), Set.of(role), NOW);
    }

    @Test
    void bootstrap_quandConfigurationValideEtEmailLibre_alorsCreeMemberSeulActifHacheEtDate() {
        // When
        DevMemberBootstrapResult result = service.bootstrap(new BootstrapDevMemberCommand(" Membre@Example.org ", PASSWORD));
        // Then
        assertThat(result.outcome()).isEqualTo(DevMemberBootstrapOutcome.CREATED);
        assertThat(users.saved()).hasSize(1);
        User saved = users.saved().get(0);
        assertThat(result.createdUserId()).isEqualTo(saved.id());
        assertThat(saved.email().value()).isEqualTo("membre@example.org");
        assertThat(saved.roles()).containsExactly(Role.MEMBER);
        assertThat(saved.enabled()).isTrue();
        assertThat(saved.createdAt()).isEqualTo(NOW);
        assertThat(saved.passwordHash()).isEqualTo(FakePasswordHasher.hashOf(PASSWORD));
        assertThat(hasher.hashCalls()).isEqualTo(1);
    }

    @Test
    void bootstrap_quandUtilisateurAvecMemeEmailEtRoleMember_alorsRienCreeNiHache() {
        // Given
        users.givenUser(existing("membre@example.org", Role.MEMBER));
        // When
        DevMemberBootstrapResult result = service.bootstrap(new BootstrapDevMemberCommand("membre@example.org", PASSWORD));
        // Then
        assertThat(result.outcome()).isEqualTo(DevMemberBootstrapOutcome.ALREADY_PRESENT);
        assertThat(result.createdUserId()).isNull();
        assertThat(users.saved()).isEmpty();
        assertThat(hasher.hashCalls()).isZero();
    }

    @Test
    void bootstrap_quandUtilisateurAvecMemeEmailSansRoleMember_alorsRienCreeNiModifieNiHache() {
        // Given
        User librarian = existing("membre@example.org", Role.LIBRARIAN);
        users.givenUser(librarian);
        // When
        DevMemberBootstrapResult result = service.bootstrap(new BootstrapDevMemberCommand("membre@example.org", PASSWORD));
        // Then
        assertThat(result.outcome()).isEqualTo(DevMemberBootstrapOutcome.EMAIL_TAKEN_WITHOUT_MEMBER_ROLE);
        assertThat(users.saved()).isEmpty();
        assertThat(users.loadByEmail(Email.of("membre@example.org"))).contains(librarian);
        assertThat(hasher.hashCalls()).isZero();
    }

    @Test
    void bootstrap_quandAppelDeuxFois_alorsIdempotent() {
        // Given
        BootstrapDevMemberCommand command = new BootstrapDevMemberCommand("membre@example.org", PASSWORD);
        // When
        DevMemberBootstrapResult first = service.bootstrap(command);
        DevMemberBootstrapResult second = service.bootstrap(command);
        // Then
        assertThat(first.outcome()).isEqualTo(DevMemberBootstrapOutcome.CREATED);
        assertThat(second.outcome()).isEqualTo(DevMemberBootstrapOutcome.ALREADY_PRESENT);
        assertThat(users.saved()).hasSize(1);
        assertThat(hasher.hashCalls()).isEqualTo(1);
    }

    @Test
    void bootstrap_quandVariablesAbsentesOuVides_alorsMissingConfigurationSansEffet() {
        // When / Then : les quatre cas du plan de scénario
        assertThat(service.bootstrap(new BootstrapDevMemberCommand(null, PASSWORD)).outcome())
                .isEqualTo(DevMemberBootstrapOutcome.MISSING_CONFIGURATION);
        assertThat(service.bootstrap(new BootstrapDevMemberCommand("membre@example.org", null)).outcome())
                .isEqualTo(DevMemberBootstrapOutcome.MISSING_CONFIGURATION);
        assertThat(service.bootstrap(new BootstrapDevMemberCommand(null, null)).outcome())
                .isEqualTo(DevMemberBootstrapOutcome.MISSING_CONFIGURATION);
        assertThat(service.bootstrap(new BootstrapDevMemberCommand("membre@example.org", "")).outcome())
                .isEqualTo(DevMemberBootstrapOutcome.MISSING_CONFIGURATION);
        assertThat(users.saved()).isEmpty();
        assertThat(hasher.hashCalls()).isZero();
    }

    @Test
    void bootstrap_quandEmailInvalide_alorsInvalidEmailSansHachage() {
        // When
        DevMemberBootstrapResult result = service.bootstrap(new BootstrapDevMemberCommand("pas-un-email", PASSWORD));
        // Then
        assertThat(result.outcome()).isEqualTo(DevMemberBootstrapOutcome.INVALID_EMAIL);
        assertThat(users.saved()).isEmpty();
        assertThat(hasher.hashCalls()).isZero();
    }

    @Test
    void bootstrap_quandMotDePasseFaible_alorsWeakPasswordSansHachage() {
        // When
        DevMemberBootstrapResult result = service.bootstrap(new BootstrapDevMemberCommand("membre@example.org", "court"));
        // Then
        assertThat(result.outcome()).isEqualTo(DevMemberBootstrapOutcome.WEAK_PASSWORD);
        assertThat(users.saved()).isEmpty();
        assertThat(hasher.hashCalls()).isZero();
    }

    @Test
    void command_quandToString_alorsEmailEtMotDePasseMasques() {
        // When
        String text = new BootstrapDevMemberCommand("membre@example.org", PASSWORD).toString();
        // Then
        assertThat(text).doesNotContain("membre@example.org").doesNotContain(PASSWORD);
    }
}
