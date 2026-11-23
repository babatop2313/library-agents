package com.example.library.identity.adapter.in.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.library.identity.application.port.in.BootstrapLibrarianCommand;
import com.example.library.identity.application.port.in.BootstrapLibrarianUseCase;
import com.example.library.identity.application.port.in.BootstrapResult;
import com.example.library.identity.config.LibrarianBootstrapProperties;
import com.example.library.identity.domain.model.BootstrapOutcome;
import com.example.library.identity.domain.model.UserId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;

/** RM-AUTH-08 : niveau de journal par issue, aucun mot de passe, démarrage jamais bloqué. */
class LibrarianBootstrapRunnerTest {

    private static final String PASSWORD = "Sup3r-Secret-Passw0rd";

    private final BootstrapLibrarianUseCase useCase = mock(BootstrapLibrarianUseCase.class);
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(LibrarianBootstrapRunner.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private void run(String email, String password) {
        new LibrarianBootstrapRunner(useCase, new LibrarianBootstrapProperties(email, password))
                .run(new DefaultApplicationArguments());
    }

    private ILoggingEvent onlyEvent() {
        assertThat(logs.list).hasSize(1);
        return logs.list.get(0);
    }

    @Test
    void run_quandCreated_alorsInfoAvecUserIdSansEmailNiMotDePasse() {
        // Given
        UserId id = new UserId(UUID.randomUUID());
        when(useCase.bootstrap(any())).thenReturn(BootstrapResult.created(id));

        // When
        run("admin@example.org", PASSWORD);

        // Then
        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains(id.value().toString())
                .doesNotContain("admin@example.org").doesNotContain(PASSWORD);
    }

    @Test
    void run_quandAlreadyPresent_alorsDebugSeulement() {
        // Given
        when(useCase.bootstrap(any())).thenReturn(BootstrapResult.of(BootstrapOutcome.LIBRARIAN_ALREADY_PRESENT));

        // When
        run("admin@example.org", PASSWORD);

        // Then
        assertThat(onlyEvent().getLevel()).isEqualTo(Level.DEBUG);
    }

    @Test
    void run_quandMissingConfiguration_alorsWarnSansValeurs() {
        // Given
        when(useCase.bootstrap(any())).thenReturn(BootstrapResult.of(BootstrapOutcome.MISSING_CONFIGURATION));

        // When
        run(null, "");

        // Then
        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .isEqualTo("BOOTSTRAP_LIBRARIAN_EMAIL ou BOOTSTRAP_LIBRARIAN_PASSWORD absent : aucun bibliothécaire créé");
    }

    @Test
    void run_quandInvalidEmail_alorsWarnSansLaValeurSaisie() {
        // Given
        when(useCase.bootstrap(any())).thenReturn(BootstrapResult.of(BootstrapOutcome.INVALID_EMAIL));

        // When
        run("pas-un-email-secret", PASSWORD);

        // Then
        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).doesNotContain("pas-un-email-secret").doesNotContain(PASSWORD);
    }

    @Test
    void run_quandWeakPassword_alorsWarnSansMotDePasseNiLongueur() {
        // Given
        String weak = "court-1234";
        when(useCase.bootstrap(any())).thenReturn(BootstrapResult.of(BootstrapOutcome.WEAK_PASSWORD));

        // When
        run("admin@example.org", weak);

        // Then
        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).doesNotContain(weak).doesNotContain("admin@example.org")
                .doesNotContain(String.valueOf(weak.length()) + " caract");
    }

    @Test
    void run_quandEmailPrisParUnNonBibliothecaire_alorsWarnAvecEmailMasqueSeulement() {
        // Given
        when(useCase.bootstrap(any())).thenReturn(BootstrapResult.of(BootstrapOutcome.EMAIL_TAKEN_BY_NON_LIBRARIAN));

        // When
        run(" Admin@Example.org ", PASSWORD);

        // Then
        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("a***@example.org")
                .doesNotContain("admin@example.org").doesNotContain(PASSWORD);
    }

    @Test
    void run_quandConflitDIntegrite_alorsWarnSansMessageDuSgbdEtDemarrageContinue() {
        // Given : le message du SGBD contient l'e-mail et la valeur dupliquée
        when(useCase.bootstrap(any())).thenThrow(
                new DataIntegrityViolationException("Key (lower(email))=(admin@example.org) already exists"));

        // When / Then
        assertThatCode(() -> run("admin@example.org", PASSWORD)).doesNotThrowAnyException();
        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).doesNotContain("admin@example.org").doesNotContain(PASSWORD);
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void run_quandErreurInattendue_alorsWarnSansStackTraceEtDemarrageContinue() {
        // Given
        when(useCase.bootstrap(any())).thenThrow(new IllegalStateException("contient " + PASSWORD));

        // When / Then
        assertThatCode(() -> run("admin@example.org", PASSWORD)).doesNotThrowAnyException();
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("IllegalStateException").doesNotContain(PASSWORD));
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void run_quandErreurInattendueEnDebug_alorsDetailDesCausesEtLieuxSansAucunMessage() {
        // Given : le message de l'exception et celui de sa cause contiennent des valeurs sensibles
        when(useCase.bootstrap(any())).thenThrow(new IllegalStateException("contient " + PASSWORD,
                new IllegalArgumentException("admin@example.org")));

        // When
        run("admin@example.org", PASSWORD);

        // Then
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.DEBUG).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("java.lang.IllegalStateException at ")
                    .contains(" <- java.lang.IllegalArgumentException at ")
                    .doesNotContain(PASSWORD).doesNotContain("admin@example.org");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void run_quandErreurInattendueHorsDebug_alorsAucunDetail() {
        // Given
        logger.setLevel(Level.INFO);
        when(useCase.bootstrap(any())).thenThrow(new IllegalStateException("boom"));

        // When
        run("admin@example.org", PASSWORD);

        // Then
        assertThat(onlyEvent().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void run_quandAppel_alorsLaCommandeContientLesValeursConfigureesEtRienNeFuitParToString() {
        // Given
        when(useCase.bootstrap(any())).thenReturn(BootstrapResult.of(BootstrapOutcome.LIBRARIAN_ALREADY_PRESENT));

        // When
        run("admin@example.org", PASSWORD);

        // Then
        ArgumentCaptor<BootstrapLibrarianCommand> captor = ArgumentCaptor.forClass(BootstrapLibrarianCommand.class);
        Mockito.verify(useCase).bootstrap(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("admin@example.org");
        assertThat(captor.getValue().password()).isEqualTo(PASSWORD);
        assertThat(captor.getValue().toString()).doesNotContain(PASSWORD).doesNotContain("admin@example.org");
        assertThat(new LibrarianBootstrapProperties("admin@example.org", PASSWORD).toString())
                .doesNotContain(PASSWORD).doesNotContain("admin@example.org");
    }

    @Test
    void run_quandToutesLesIssues_alorsAucunLogNeContientLeMotDePasse() {
        // Given
        List<BootstrapOutcome> outcomes = List.of(BootstrapOutcome.values());
        UserId id = new UserId(UUID.randomUUID());

        // When
        for (BootstrapOutcome outcome : outcomes) {
            when(useCase.bootstrap(any())).thenReturn(
                    outcome == BootstrapOutcome.CREATED ? BootstrapResult.created(id) : BootstrapResult.of(outcome));
            run("admin@example.org", PASSWORD);
        }

        // Then
        assertThat(logs.list).hasSize(outcomes.size());
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(PASSWORD));
    }
}
