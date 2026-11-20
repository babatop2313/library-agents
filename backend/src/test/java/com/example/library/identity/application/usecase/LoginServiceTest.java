package com.example.library.identity.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.library.identity.application.fake.FakeAccessTokenIssuer;
import com.example.library.identity.application.fake.FakePasswordHasher;
import com.example.library.identity.application.fake.InMemoryUserRepository;
import com.example.library.identity.application.port.in.LoginCommand;
import com.example.library.identity.application.port.in.LoginResult;
import com.example.library.identity.domain.exception.InvalidCredentialsException;
import com.example.library.identity.domain.exception.InvalidEmailException;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.User;
import com.example.library.identity.domain.model.UserId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** RM-AUTH-05, 06, 07, 11 : orchestration de la connexion (fakes des ports, Clock fixe). */
class LoginServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00.789Z");
    private static final String PASSWORD = "S3cret!pass-long";

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final FakePasswordHasher hasher = new FakePasswordHasher();
    private final FakeAccessTokenIssuer issuer = new FakeAccessTokenIssuer();
    private final LoginService service =
            new LoginService(users, hasher, issuer, Clock.fixed(NOW, ZoneOffset.UTC));

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(LoginService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private User givenUser(String email, boolean enabled, Role... roles) {
        User user = User.restore(UserId.newId(), Email.of(email), FakePasswordHasher.hashOf(PASSWORD), enabled,
                Set.of(roles), Instant.parse("2026-01-01T00:00:00Z"));
        users.givenUser(user);
        return user;
    }

    private List<String> formattedLogs() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void login_quandIdentifiantsCorrects_alorsRenvoieUtilisateurEtJeton() {
        // Given
        User user = givenUser("alice@example.org", true, Role.MEMBER);

        // When
        LoginResult result = service.login(new LoginCommand("alice@example.org", PASSWORD));

        // Then
        assertThat(result.userId()).isEqualTo(user.id());
        assertThat(result.email().value()).isEqualTo("alice@example.org");
        assertThat(result.roles()).containsExactly(Role.MEMBER);
        assertThat(result.accessToken().value()).isEqualTo("fake-jwt-for-" + user.id().value());
        assertThat(issuer.lastIssuedAt()).isEqualTo(NOW);
        assertThat(issuer.lastUser()).isSameAs(user);
        assertThat(hasher.matchesCalls()).isEqualTo(1);
    }

    @Test
    void login_quandEmailInconnu_alorsInvalidCredentialsEtBcryptExecuteUneFoisAvecHashFactice() {
        // Given : aucun utilisateur

        // When / Then
        assertThatThrownBy(() -> service.login(new LoginCommand("inconnu@example.org", PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(hasher.matchesCalls()).isEqualTo(1);
        assertThat(hasher.lastReference()).isEqualTo(FakePasswordHasher.DUMMY);
        assertThat(issuer.calls()).isZero();
    }

    @Test
    void login_quandMauvaisMotDePasse_alorsInvalidCredentialsSansJeton() {
        // Given
        givenUser("alice@example.org", true, Role.MEMBER);

        // When / Then
        assertThatThrownBy(() -> service.login(new LoginCommand("alice@example.org", "mauvais-mot-de-passe")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("E-mail ou mot de passe incorrect");
        assertThat(hasher.matchesCalls()).isEqualTo(1);
        assertThat(issuer.calls()).isZero();
    }

    @Test
    void login_quandCompteDesactive_alorsInvalidCredentialsMemeAvecBonMotDePasse() {
        // Given
        givenUser("alice@example.org", false, Role.MEMBER);

        // When / Then
        assertThatThrownBy(() -> service.login(new LoginCommand("alice@example.org", PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(hasher.matchesCalls()).isEqualTo(1);
        assertThat(issuer.calls()).isZero();
    }

    @Test
    void login_quandMotDePasseDe73Octets_alorsInvalidCredentialsEtComparaisonExecutee() {
        // Given
        givenUser("alice@example.org", true, Role.MEMBER);

        // When / Then
        assertThatThrownBy(() -> service.login(new LoginCommand("alice@example.org", "a".repeat(73))))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(hasher.matchesCalls()).isEqualTo(1);
    }

    @Test
    void login_quandEmailEnMajusculesEtEspaces_alorsNormaliseEtAuthentifie() {
        // Given
        givenUser("alice@example.org", true, Role.MEMBER);

        // When
        LoginResult result = service.login(new LoginCommand("  Alice@Example.ORG  ", PASSWORD));

        // Then
        assertThat(result.email().value()).isEqualTo("alice@example.org");
    }

    @Test
    void login_quandEmailMalForme_alorsInvalidEmailSansComparaison() {
        // When / Then
        assertThatThrownBy(() -> service.login(new LoginCommand("pas-un-email", PASSWORD)))
                .isInstanceOf(InvalidEmailException.class);
        assertThat(hasher.matchesCalls()).isZero();
    }

    @Test
    void login_quandRolesCumules_alorsLesDeuxRolesRenvoyes() {
        // Given
        givenUser("alice@example.org", true, Role.MEMBER, Role.LIBRARIAN);

        // When
        LoginResult result = service.login(new LoginCommand("alice@example.org", PASSWORD));

        // Then
        assertThat(result.roles()).containsExactlyInAnyOrder(Role.MEMBER, Role.LIBRARIAN);
    }

    @Test
    void login_quandSucces_alorsInfoAvecUserIdSansEmailNiMotDePasse() {
        // Given
        User user = givenUser("alice@example.org", true, Role.MEMBER);

        // When
        service.login(new LoginCommand("alice@example.org", PASSWORD));

        // Then
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(formattedLogs().get(0))
                .contains(user.id().value().toString())
                .doesNotContain("alice")
                .doesNotContain("example.org")
                .doesNotContain(PASSWORD)
                .doesNotContain("fake-jwt");
    }

    @Test
    void login_quandEchec_alorsWarnAvecEmailMasqueSansMotDePasseNiEmailEnClair() {
        // Given
        givenUser("alice@example.org", true, Role.MEMBER);

        // When
        assertThatThrownBy(() -> service.login(new LoginCommand("Alice@example.org", "mauvais-mot-de-passe")))
                .isInstanceOf(InvalidCredentialsException.class);

        // Then
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(formattedLogs().get(0))
                .contains("a***@example.org")
                .doesNotContain("alice@example.org")
                .doesNotContain("mauvais-mot-de-passe");
    }

    @Test
    void login_quandEchec_alorsAucunIdentifiantUtilisateurDansLeWarn() {
        // Given
        User user = givenUser("alice@example.org", false, Role.MEMBER);

        // When
        assertThatThrownBy(() -> service.login(new LoginCommand("alice@example.org", PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);

        // Then
        assertThat(formattedLogs().get(0)).doesNotContain(user.id().value().toString());
    }
}
