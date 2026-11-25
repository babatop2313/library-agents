package com.example.library.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.SpringFactoriesLoader;

/**
 * RM-LOAN-29 : le {@code Clock} de l'application est construit sur le fuseau {@code app.timezone}. Scénarios de
 * configuration : absent, vide, valide, invalide (démarrage refusé, message explicite, pas de repli).
 */
class ClockConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ClockConfig.class);

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(ClockConfig.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void clock_quandProprieteAbsente_alorsFuseauAfricaDakar() {
        // When / Then
        runner.run(context -> assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Africa/Dakar")));
    }

    @Test
    void clock_quandProprieteVide_alorsFuseauAfricaDakar() {
        // When / Then
        runner.withPropertyValues("app.timezone=").run(context ->
                assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Africa/Dakar")));
    }

    @Test
    void clock_quandPropieteBlanche_alorsFuseauAfricaDakar() {
        // When / Then
        runner.withPropertyValues("app.timezone=   ").run(context ->
                assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Africa/Dakar")));
    }

    @Test
    void clock_quandEuropeParisAvecEspaces_alorsFuseauParis() {
        // When / Then
        runner.withPropertyValues("app.timezone= Europe/Paris ").run(context ->
                assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Europe/Paris")));
    }

    @Test
    void clock_quandFuseauValide_alorsFuseauEffectifJournaliseEnInfo() {
        // When
        runner.withPropertyValues("app.timezone=Asia/Tokyo").run(context -> { });
        // Then
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("Asia/Tokyo");
        });
    }

    @Test
    void clock_quandApplicationYamlSansVariable_alorsFuseauParDefautDeLApplicationYaml() {
        // Given : application.yml réel, APP_TIMEZONE absente (variables d'environnement du poste retirées)
        // When / Then
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withUserConfiguration(ClockConfig.class)
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty("app.timezone")).isEqualTo("Africa/Dakar");
                    assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Africa/Dakar"));
                });
    }

    @Test
    void clock_quandApplicationYamlEtVariableVide_alorsFuseauAfricaDakar() {
        // Given : APP_TIMEZONE= dans .env (définie mais vide) avec l'application.yml réel
        // When / Then
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("APP_TIMEZONE=")
                .withUserConfiguration(ClockConfig.class)
                .run(context ->
                        assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Africa/Dakar")));
    }

    @Test
    void clock_quandApplicationYamlEtVariableEuropeParis_alorsFuseauParis() {
        // Given
        // When / Then
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("APP_TIMEZONE=Europe/Paris")
                .withUserConfiguration(ClockConfig.class)
                .run(context ->
                        assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Europe/Paris")));
    }

    @Test
    void clock_quandFuseauInvalide_alorsDemarrageEnEchecQuiNommeLaProprieteEtLaValeur() {
        // When / Then : aucun repli sur Africa/Dakar
        runner.withPropertyValues("app.timezone=Mars/Olympus").run(context -> {
            assertThat(context).hasFailed();
            Throwable root = rootCauseOf(context.getStartupFailure());
            assertThat(root).isInstanceOf(InvalidTimeZoneException.class);
            assertThat(root.getMessage()).contains("app.timezone").contains("Mars/Olympus");
        });
    }

    @Test
    void clock_quandDecalageHorsBornes_alorsDemarrageEnEchec() {
        // When / Then
        runner.withPropertyValues("app.timezone=+25:00").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure()).getMessage()).contains("app.timezone").contains("+25:00");
        });
    }

    @Test
    void failureAnalyzer_quandFuseauInvalide_alorsDescriptionAvecProprieteEtValeur() {
        // Given
        InvalidTimeZoneException failure = new InvalidTimeZoneException("app.timezone", "Mars/Olympus",
                new IllegalArgumentException("cause"));
        // When
        FailureAnalysis analysis = new InvalidTimeZoneFailureAnalyzer().analyze(new RuntimeException("wrap", failure));
        // Then
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("app.timezone").contains("APP_TIMEZONE")
                .contains("Mars/Olympus");
        assertThat(analysis.getAction()).contains("Africa/Dakar");
    }

    @Test
    void failureAnalyzer_quandSpringFactories_alorsEnregistre() {
        // When
        List<String> analyzers = SpringFactoriesLoader.loadFactoryNames(FailureAnalyzer.class, getClass().getClassLoader());
        // Then
        assertThat(analyzers).contains(InvalidTimeZoneFailureAnalyzer.class.getName());
    }

    private static Throwable rootCauseOf(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        // la cause racine est celle de DateTimeException : on remonte à l'exception métier
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof InvalidTimeZoneException) {
                return cursor;
            }
            cursor = cursor.getCause();
        }
        return current;
    }
}
