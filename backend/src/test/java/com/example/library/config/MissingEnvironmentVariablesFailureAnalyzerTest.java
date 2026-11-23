package com.example.library.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

class MissingEnvironmentVariablesFailureAnalyzerTest {

    private final MissingEnvironmentVariablesFailureAnalyzer analyzer =
            new MissingEnvironmentVariablesFailureAnalyzer();

    /** Au démarrage réel, l'exception peut arriver enveloppée : l'analyseur doit retrouver la cause. */
    private static Throwable wrapped(MissingEnvironmentVariablesException cause) {
        return new IllegalStateException("Échec du démarrage", cause);
    }

    @Test
    void analyze_quandVariablesManquantesEnveloppees_alorsDescriptionListeLesNoms() {
        // Given
        Throwable failure = wrapped(new MissingEnvironmentVariablesException(List.of("DB_USER", "JWT_SECRET")));

        // When
        FailureAnalysis analysis = analyzer.analyze(failure);

        // Then
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("DB_USER").contains("JWT_SECRET");
        assertThat(analysis.getDescription()).doesNotContain("DB_PASSWORD");
    }

    @Test
    void analyze_quandVariablesManquantesEnveloppees_alorsActionMentionneLeFichierEnv() {
        // Given
        Throwable failure = wrapped(new MissingEnvironmentVariablesException(List.of("DB_PASSWORD")));

        // When
        FailureAnalysis analysis = analyzer.analyze(failure);

        // Then
        assertThat(analysis).isNotNull();
        assertThat(analysis.getAction()).contains(".env");
    }

    @Test
    void analyze_quandAutreEchec_alorsAucuneAnalyse() {
        // Given
        Throwable failure = new IllegalStateException("autre cause");

        // When / Then
        assertThat(analyzer.analyze(failure)).isNull();
    }
}
