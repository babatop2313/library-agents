package com.example.library.identity.adapter.in.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.library.identity.application.port.in.BootstrapDevMemberCommand;
import com.example.library.identity.application.port.in.BootstrapDevMemberUseCase;
import com.example.library.identity.application.port.in.DevMemberBootstrapResult;
import com.example.library.identity.config.DevMemberProperties;
import com.example.library.identity.domain.model.DevMemberBootstrapOutcome;
import com.example.library.identity.domain.model.UserId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * RM-LOAN-30 : niveau de journal par issue, aucun mot de passe, démarrage jamais bloqué, composant réservé au profil
 * local.
 */
class DevMemberBootstrapRunnerTest {

    private static final String EMAIL = "membre.test@example.org";
    private static final String PASSWORD = "Sup3r-Secret-Passw0rd";

    private final BootstrapDevMemberUseCase useCase = mock(BootstrapDevMemberUseCase.class);
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(DevMemberBootstrapRunner.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
    }

    private void run(String email, String password) {
        new DevMemberBootstrapRunner(useCase, new DevMemberProperties(email, password))
                .run(new DefaultApplicationArguments());
    }

    private void givenOutcome(DevMemberBootstrapOutcome outcome) {
        when(useCase.bootstrap(any())).thenReturn(DevMemberBootstrapResult.of(outcome));
    }

    private ILoggingEvent onlyEvent() {
        assertThat(logs.list).hasSize(1);
        return logs.list.get(0);
    }

    private void assertNoSecretLogged() {
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(PASSWORD);
            assertThat(String.valueOf(event.getThrowableProxy())).doesNotContain(PASSWORD);
            assertThat(event.getArgumentArray() == null ? "" : java.util.Arrays.toString(event.getArgumentArray()))
                    .doesNotContain(PASSWORD);
        });
    }

    @Test
    void run_quandCreated_alorsInfoAvecUserIdSansEmailNiMotDePasse() {
        // Given
        UserId id = new UserId(UUID.randomUUID());
        when(useCase.bootstrap(any())).thenReturn(DevMemberBootstrapResult.created(id));
        // When
        run(EMAIL, PASSWORD);
        // Then
        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains(id.value().toString())
                .doesNotContain(EMAIL).doesNotContain(PASSWORD);
    }

    @Test
    void run_quandAppel_alorsLeUseCaseRecoitLesValeursBrutesDesPropriete() {
        // Given
        givenOutcome(DevMemberBootstrapOutcome.ALREADY_PRESENT);
        // When
        run(EMAIL, PASSWORD);
        // Then
        ArgumentCaptor<BootstrapDevMemberCommand> captor = ArgumentCaptor.forClass(BootstrapDevMemberCommand.class);
        verify(useCase).bootstrap(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo(EMAIL);
        assertThat(captor.getValue().password()).isEqualTo(PASSWORD);
    }

    @Test
    void run_quandMissingConfiguration_alorsWarnFigeSansValeur() {
        // Given
        givenOutcome(DevMemberBootstrapOutcome.MISSING_CONFIGURATION);
        // When
        run(null, "");
        // Then
        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .isEqualTo("DEV_MEMBER_EMAIL ou DEV_MEMBER_PASSWORD absent : aucun membre de test créé");
    }

    @Test
    void run_quandInvalidEmail_alorsWarnSansEmailNiMotDePasse() {
        // Given
        givenOutcome(DevMemberBootstrapOutcome.INVALID_EMAIL);
        // When
        run("pas-un-email", PASSWORD);
        // Then
        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).doesNotContain("pas-un-email").doesNotContain(PASSWORD);
    }

    @Test
    void run_quandWeakPassword_alorsWarnSansMotDePasseNiSaLongueur() {
        // Given
        givenOutcome(DevMemberBootstrapOutcome.WEAK_PASSWORD);
        // When
        run(EMAIL, "court");
        // Then
        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).doesNotContain("court").doesNotContain(EMAIL);
    }

    @Test
    void run_quandEmailPrisSansRoleMember_alorsWarnAvecEmailMasqueSeulement() {
        // Given
        givenOutcome(DevMemberBootstrapOutcome.EMAIL_TAKEN_WITHOUT_MEMBER_ROLE);
        // When
        run(EMAIL, PASSWORD);
        // Then
        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).doesNotContain(EMAIL).doesNotContain(PASSWORD).contains("***");
        assertNoSecretLogged();
    }

    @Test
    void run_quandAlreadyPresent_alorsAucunWarn() {
        // Given
        givenOutcome(DevMemberBootstrapOutcome.ALREADY_PRESENT);
        // When
        run(EMAIL, PASSWORD);
        // Then : idempotent et silencieux (DEBUG seulement)
        assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.INFO));
    }

    @Test
    void run_quandCreationConcurrente_alorsWarnSansMessageDuSgbdEtDemarrageNonBloque() {
        // Given : le message du SGBD peut contenir l'e-mail
        when(useCase.bootstrap(any())).thenThrow(new DataIntegrityViolationException("clé (email)=(" + EMAIL + ")"));
        // When / Then
        assertThatCode(() -> run(EMAIL, PASSWORD)).doesNotThrowAnyException();
        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).doesNotContain(EMAIL);
    }

    @Test
    void run_quandErreurInattendue_alorsWarnAvecNomDeClasseSeulementEtDemarrageNonBloque() {
        // Given
        when(useCase.bootstrap(any())).thenThrow(new IllegalStateException("détail avec " + PASSWORD));
        // When / Then
        assertThatCode(() -> run(EMAIL, PASSWORD)).doesNotThrowAnyException();
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("IllegalStateException").doesNotContain(PASSWORD);
        });
    }

    @Test
    void runner_quandClasse_alorsReserveAuProfilLocal() {
        // When
        Profile profile = DevMemberBootstrapRunner.class.getAnnotation(Profile.class);
        // Then
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("local");
    }

    @Test
    void contexte_quandProfilLocal_alorsRunnerPresent() {
        // Given / When / Then
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withBean(BootstrapDevMemberUseCase.class, () -> useCase)
                .withBean(DevMemberProperties.class, () -> new DevMemberProperties(EMAIL, PASSWORD))
                .withUserConfiguration(DevMemberBootstrapRunner.class)
                .run(context -> assertThat(context).hasSingleBean(DevMemberBootstrapRunner.class));
    }

    @Test
    void contexte_quandProfilAutreQueLocal_alorsAucunRunner() {
        // Given / When / Then : variables définies mais ignorées hors profil local
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withBean(BootstrapDevMemberUseCase.class, () -> useCase)
                .withBean(DevMemberProperties.class, () -> new DevMemberProperties(EMAIL, PASSWORD))
                .withUserConfiguration(DevMemberBootstrapRunner.class)
                .run(context -> assertThat(context).doesNotHaveBean(DevMemberBootstrapRunner.class));
    }

    @Test
    void contexte_quandAucunProfil_alorsAucunRunner() {
        // Given / When / Then
        new ApplicationContextRunner()
                .withBean(BootstrapDevMemberUseCase.class, () -> useCase)
                .withBean(DevMemberProperties.class, () -> new DevMemberProperties(EMAIL, PASSWORD))
                .withUserConfiguration(DevMemberBootstrapRunner.class)
                .run(context -> assertThat(context).doesNotHaveBean(DevMemberBootstrapRunner.class));
    }

    @Test
    void properties_quandToString_alorsEmailEtMotDePasseMasques() {
        // When
        String text = new DevMemberProperties(EMAIL, PASSWORD).toString();
        // Then
        assertThat(text).doesNotContain(EMAIL).doesNotContain(PASSWORD);
    }
}
