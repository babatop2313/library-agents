package com.example.library.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/** Garde-fou : application.yml ne donne aucune valeur par défaut aux secrets. */
class ApplicationYamlSecretsTest {

    private static final Path MAIN_RESOURCES = Path.of("src", "main", "resources");
    private static final List<String> SECRET_PROPERTIES =
            List.of("spring.datasource.username", "spring.datasource.password", "app.security.jwt.secret");

    /** Chargé par son chemin réel (Maven lance les tests depuis backend/), pas par le classpath de test. */
    private static List<PropertySource<?>> load(Path file) throws IOException {
        return new YamlPropertySourceLoader().load(file.getFileName().toString(), new FileSystemResource(file));
    }

    private PropertySource<?> mainApplicationYaml() throws IOException {
        return load(MAIN_RESOURCES.resolve("application.yml")).get(0);
    }

    @Test
    void profilsApplicationYml_quandCharges_alorsAucunNeRedefinitLesSecrets() throws IOException {
        // Given : tous les application-<profil>.yml de main
        List<Path> profiles;
        try (Stream<Path> files = Files.list(MAIN_RESOURCES)) {
            profiles = files.filter(p -> p.getFileName().toString().matches("application-.+\\.ya?ml")).toList();
        }
        assertThat(profiles).isNotEmpty();

        // When / Then : aucun profil ne fournit de valeur pour un secret
        for (Path profile : profiles) {
            for (PropertySource<?> source : load(profile)) {
                for (String property : SECRET_PROPERTIES) {
                    assertThat(source.getProperty(property)).as("%s dans %s", property, profile.getFileName()).isNull();
                }
            }
        }
    }

    @Test
    void applicationYml_quandChargeBrut_alorsDbUserSansValeurParDefaut() throws IOException {
        // When
        Object raw = mainApplicationYaml().getProperty("spring.datasource.username");

        // Then
        assertThat(raw).isEqualTo("${DB_USER}");
    }

    @Test
    void applicationYml_quandChargeBrut_alorsDbPasswordSansValeurParDefaut() throws IOException {
        // When
        Object raw = mainApplicationYaml().getProperty("spring.datasource.password");

        // Then
        assertThat(raw).isEqualTo("${DB_PASSWORD}");
    }

    @Test
    void applicationYml_quandChargeBrut_alorsJwtSecretSansValeurParDefaut() throws IOException {
        // When
        Object raw = mainApplicationYaml().getProperty("app.security.jwt.secret");

        // Then
        assertThat(raw).isEqualTo("${JWT_SECRET}");
    }
}
