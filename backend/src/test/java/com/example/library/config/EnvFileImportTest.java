package com.example.library.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Un .env importé via spring.config.import (property source de config data) satisfait RequiredEnvironmentVariables.
 * Le .env est un fichier jetable du dossier temporaire ; l'application.properties de test est écarté
 * ({@code spring.config.name}) pour qu'il ne masque pas les valeurs du .env.
 */
class EnvFileImportTest {

    @Configuration
    static class EmptyConfig {}

    private static ConfigurableApplicationContext start(Path envFile) {
        String location = envFile.toAbsolutePath().toString().replace('\\', '/');
        return new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.config.name=absent-pour-ce-test",
                        "spring.main.banner-mode=off",
                        "spring.config.import=optional:file:" + location + "[.properties]")
                .run();
    }

    private static void assumeSystemEnvIsClean() {
        assumeTrue(
                System.getenv("DB_USER") == null
                        && System.getenv("DB_PASSWORD") == null
                        && System.getenv("JWT_SECRET") == null,
                "variables système déjà définies : test non significatif");
        assumeTrue(
                System.getProperty("DB_USER") == null
                        && System.getProperty("DB_PASSWORD") == null
                        && System.getProperty("JWT_SECRET") == null,
                "propriétés système déjà définies : test non significatif");
    }

    @Test
    void demarrage_quandSecretsFournisParUnEnvImporte_alorsAccepte(@TempDir Path dir) throws IOException {
        // Given : un .env jetable avec les trois secrets obligatoires
        assumeSystemEnvIsClean();
        Path env = Files.writeString(dir.resolve(".env"), "DB_USER=u-test\nDB_PASSWORD=p-test\nJWT_SECRET=s-test\n");

        // When
        try (ConfigurableApplicationContext context = start(env)) {
            // Then : les valeurs viennent du fichier importé
            assertThat(context.getEnvironment().getProperty("DB_USER")).isEqualTo("u-test");
            assertThat(context.getEnvironment().getProperty("JWT_SECRET")).isEqualTo("s-test");
        }
    }

    @Test
    void demarrage_quandEnvImporteIncomplet_alorsRefuse(@TempDir Path dir) throws IOException {
        // Given : un .env jetable sans DB_PASSWORD
        assumeSystemEnvIsClean();
        Path env = Files.writeString(dir.resolve(".env"), "DB_USER=u-test\nJWT_SECRET=s-test\n");

        // When / Then
        assertThatThrownBy(() -> start(env).close())
                .isInstanceOf(MissingEnvironmentVariablesException.class)
                .satisfies(e -> assertThat(((MissingEnvironmentVariablesException) e).missing())
                        .containsExactly("DB_PASSWORD"));
    }
}
