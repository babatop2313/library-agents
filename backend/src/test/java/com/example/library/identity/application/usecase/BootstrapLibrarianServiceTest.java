package com.example.library.identity.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import com.example.library.identity.application.fake.FakePasswordHasher;
import com.example.library.identity.application.fake.InMemoryUserRepository;
import com.example.library.identity.application.port.in.BootstrapLibrarianCommand;
import com.example.library.identity.application.port.in.BootstrapResult;
import com.example.library.identity.domain.model.BootstrapOutcome;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.PasswordHash;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.User;
import org.junit.jupiter.api.Test;

/** RM-AUTH-08 : orchestration de l'amorçage ; la décision est testée dans le domaine. */
class BootstrapLibrarianServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final String PASSWORD = "S3cret!pass-long";

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final FakePasswordHasher hasher = new FakePasswordHasher();
    private final BootstrapLibrarianService service =
            new BootstrapLibrarianService(users, users, users, hasher, Clock.fixed(NOW, ZoneOffset.UTC));

    private static User existing(String email, Role role) {
        return User.register(Email.of(email), new PasswordHash("$2a$10$x"), Set.of(role), NOW);
    }

    @Test
    void bootstrap_quandAucunBibliothecaireEtConfigValide_alorsCreeLibrarianSeulNormaliseAvecDate() {
        // When
        BootstrapResult result = service.bootstrap(new BootstrapLibrarianCommand(" Admin@Example.org ", PASSWORD));

        // Then
        assertThat(result.outcome()).isEqualTo(BootstrapOutcome.CREATED);
        assertThat(users.saved()).hasSize(1);
        User saved = users.saved().get(0);
        assertThat(result.createdUserId()).isEqualTo(saved.id());
        assertThat(saved.email().value()).isEqualTo("admin@example.org");
        assertThat(saved.roles()).containsExactly(Role.LIBRARIAN);
        assertThat(saved.enabled()).isTrue();
        assertThat(saved.createdAt()).isEqualTo(NOW);
        assertThat(saved.passwordHash()).isEqualTo(FakePasswordHasher.hashOf(PASSWORD));
        assertThat(hasher.hashCalls()).isEqualTo(1);
    }

    @Test
    void bootstrap_quandBibliothecaireDejaPresent_alorsRienCreeNiHache() {
        // Given
        users.givenUser(existing("boss@example.org", Role.LIBRARIAN));

        // When
        BootstrapResult result = service.bootstrap(new BootstrapLibrarianCommand("autre@example.org", PASSWORD));

        // Then
        assertThat(result.outcome()).isEqualTo(BootstrapOutcome.LIBRARIAN_ALREADY_PRESENT);
        assertThat(result.createdUserId()).isNull();
        assertThat(users.saved()).isEmpty();
        assertThat(hasher.hashCalls()).isZero();
    }

    @Test
    void bootstrap_quandAppelDeuxFois_alorsIdempotent() {
        // Given
        BootstrapLibrarianCommand command = new BootstrapLibrarianCommand("admin@example.org", PASSWORD);

        // When
        BootstrapResult first = service.bootstrap(command);
        BootstrapResult second = service.bootstrap(new BootstrapLibrarianCommand("autre@example.org", "Autre-mot-de-passe-1"));

        // Then
        assertThat(first.outcome()).isEqualTo(BootstrapOutcome.CREATED);
        assertThat(second.outcome()).isEqualTo(BootstrapOutcome.LIBRARIAN_ALREADY_PRESENT);
        assertThat(users.saved()).hasSize(1);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void bootstrap_quandVariablesAbsentes_alorsMissingConfigurationSansHachage() {
        // When
        BootstrapResult result = service.bootstrap(new BootstrapLibrarianCommand(null, ""));

        // Then
        assertThat(result.outcome()).isEqualTo(BootstrapOutcome.MISSING_CONFIGURATION);
        assertThat(users.saved()).isEmpty();
        assertThat(hasher.hashCalls()).isZero();
    }

    @Test
    void bootstrap_quandEmailInvalide_alorsInvalidEmailSansHachage() {
        // When
        BootstrapResult result = service.bootstrap(new BootstrapLibrarianCommand("pas-un-email", PASSWORD));

        // Then
        assertThat(result.outcome()).isEqualTo(BootstrapOutcome.INVALID_EMAIL);
        assertThat(users.saved()).isEmpty();
        assertThat(hasher.hashCalls()).isZero();
    }

    @Test
    void bootstrap_quandMotDePasse11Caracteres_alorsWeakPasswordSansHachage() {
        // When
        BootstrapResult result = service.bootstrap(new BootstrapLibrarianCommand("admin@example.org", "a".repeat(11)));

        // Then
        assertThat(result.outcome()).isEqualTo(BootstrapOutcome.WEAK_PASSWORD);
        assertThat(users.saved()).isEmpty();
        assertThat(hasher.hashCalls()).isZero();
    }

    @Test
    void bootstrap_quandEmailPrisParUnMember_alorsInchangeEtRienCree() {
        // Given
        User member = existing("admin@example.org", Role.MEMBER);
        users.givenUser(member);

        // When
        BootstrapResult result = service.bootstrap(new BootstrapLibrarianCommand("Admin@example.org", PASSWORD));

        // Then
        assertThat(result.outcome()).isEqualTo(BootstrapOutcome.EMAIL_TAKEN_BY_NON_LIBRARIAN);
        assertThat(users.saved()).isEmpty();
        assertThat(hasher.hashCalls()).isZero();
        assertThat(users.loadByEmail(member.email())).containsSame(member);
        assertThat(member.roles()).containsExactly(Role.MEMBER);
    }

    @Test
    void bootstrapResult_quandFabriques_alorsIdSeulementPourCreated() {
        // When / Then
        assertThat(BootstrapResult.of(BootstrapOutcome.WEAK_PASSWORD).createdUserId()).isNull();
        assertThat(BootstrapResult.created(existing("a@example.org", Role.LIBRARIAN).id()).outcome())
                .isEqualTo(BootstrapOutcome.CREATED);
    }
}
