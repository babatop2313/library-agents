package com.example.library.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/** Les secrets (JWT_SECRET, DB_USER, DB_PASSWORD) n'ont aucune valeur par défaut : leur absence bloque le démarrage. */
class RequiredEnvironmentVariablesTest {

    private final RequiredEnvironmentVariables processor = new RequiredEnvironmentVariables();
    private final SpringApplication application = new SpringApplication();

    private MockEnvironment completeEnvironment() {
        return new MockEnvironment()
                .withProperty("JWT_SECRET", "un-secret-de-test")
                .withProperty("DB_USER", "library")
                .withProperty("DB_PASSWORD", "mot-de-passe");
    }

    @Test
    void postProcessEnvironment_quandToutesLesVariablesSontDefinies_alorsAucuneException() {
        // Given
        MockEnvironment environment = completeEnvironment();

        // When / Then
        assertThatCode(() -> processor.postProcessEnvironment(environment, application)).doesNotThrowAnyException();
    }

    @Test
    void postProcessEnvironment_quandJwtSecretAbsent_alorsExceptionQuiLeDesigne() {
        // Given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DB_USER", "library")
                .withProperty("DB_PASSWORD", "mot-de-passe");

        // When / Then
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(MissingEnvironmentVariablesException.class)
                .hasMessageContaining("JWT_SECRET")
                .satisfies(e -> assertThat(((MissingEnvironmentVariablesException) e).missing())
                        .containsExactly("JWT_SECRET"));
    }

    @Test
    void postProcessEnvironment_quandDbUserEtDbPasswordAbsents_alorsLesDeuxSontListees() {
        // Given
        MockEnvironment environment = new MockEnvironment().withProperty("JWT_SECRET", "un-secret-de-test");

        // When / Then
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(MissingEnvironmentVariablesException.class)
                .hasMessageContaining("DB_USER")
                .hasMessageContaining("DB_PASSWORD")
                .satisfies(e -> assertThat(((MissingEnvironmentVariablesException) e).missing())
                        .containsExactlyInAnyOrder("DB_USER", "DB_PASSWORD"));
    }

    @Test
    void postProcessEnvironment_quandAucuneVariable_alorsLesTroisSontListeesDansLeMessage() {
        // Given
        MockEnvironment environment = new MockEnvironment();

        // When / Then
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(MissingEnvironmentVariablesException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("DB_USER")
                .hasMessageContaining("DB_PASSWORD")
                .satisfies(e -> assertThat(((MissingEnvironmentVariablesException) e).missing())
                        .containsExactly("JWT_SECRET", "DB_USER", "DB_PASSWORD"));
    }

    @Test
    void postProcessEnvironment_quandVariableManquante_alorsLeMessageNeContientAucuneValeur() {
        // Given : des valeurs présentes et une variable manquante
        MockEnvironment environment = completeEnvironment().withProperty("DB_PASSWORD", "   ");

        // When / Then : seuls les noms apparaissent, jamais les valeurs fournies
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(MissingEnvironmentVariablesException.class)
                .hasMessageNotContaining("un-secret-de-test")
                .hasMessageNotContaining("library");
    }

    @Test
    void postProcessEnvironment_quandValeurVide_alorsConsidereeManquante() {
        // Given
        MockEnvironment environment = completeEnvironment().withProperty("JWT_SECRET", "");

        // When / Then
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(MissingEnvironmentVariablesException.class)
                .satisfies(e -> assertThat(((MissingEnvironmentVariablesException) e).missing())
                        .containsExactly("JWT_SECRET"));
    }

    @Test
    void postProcessEnvironment_quandValeurBlanche_alorsConsidereeManquante() {
        // Given
        MockEnvironment environment = completeEnvironment().withProperty("DB_PASSWORD", "   ");

        // When / Then
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(MissingEnvironmentVariablesException.class)
                .satisfies(e -> assertThat(((MissingEnvironmentVariablesException) e).missing())
                        .containsExactly("DB_PASSWORD"));
    }
}
