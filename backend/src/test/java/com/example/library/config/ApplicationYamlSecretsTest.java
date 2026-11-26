package com.example.library.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

/** Garde-fou : application.yml ne donne aucune valeur par défaut aux secrets. */
class ApplicationYamlSecretsTest {

    private static final Path MAIN_RESOURCES = Path.of("src", "main", "resources");
    private static final List<String> SECRET_PROPERTIES =
            List.of("spring.datasource.username", "spring.datasource.password", "app.security.jwt.secret");

    /** Noms de propriétés susceptibles de porter un secret ou une donnée personnelle ({@code key\b} : api-key, signing-key...). */
    private static final Pattern SENSITIVE_NAME =
            Pattern.compile("(?i)secret|password|passwd|email|username|token|key\\b|credential");
    /** Identifiants dans une URL : {@code ://utilisateur:motdepasse@hote} (groupes 1 et 2). */
    private static final Pattern URL_CREDENTIALS = Pattern.compile("://([^/@\\s]+):([^/@\\s]+)@");
    /** Mot de passe en paramètre ou option d'URL : {@code password=...} ou {@code pwd=...} (groupe 1 = valeur). */
    private static final Pattern PASSWORD_PARAMETER = Pattern.compile("(?i)\\b(?:password|pwd)=([^&;\\s]*)");
    /** Placeholder sans valeur par défaut : {@code ${NOM}}, seul identifiant toléré dans une valeur. */
    private static final Pattern BARE_PLACEHOLDER = Pattern.compile("\\$\\{[^:}]+}");

    /**
     * Propriétés dont le nom ressemble à un secret mais qui n'en sont pas. Correspondance sur le nom complet ou sur
     * son suffixe après un point. Chaque ajout doit être justifié ici ; la liste ne s'élargit jamais pour faire passer un test.
     */
    private static final Set<String> NON_SECRET_PROPERTIES = Set.of(
            // durée de vie (ISO-8601) d'un jeton : réglage technique, pas le jeton
            "token-ttl",
            // nom du cookie qui transporte le jeton, pas sa valeur
            "cookie-name",
            // adresse d'expédition fonctionnelle d'un service, pas une donnée personnelle
            "mail.from-email");

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

    @Test
    void applicationYml_quandChargeBrut_alorsBootstrapEmailADefautVide() throws IOException {
        // When
        Object raw = mainApplicationYaml().getProperty("app.bootstrap.librarian.email");

        // Then : absent = aucun compte créé (WARN), jamais d'adresse par défaut
        assertThat(raw).isEqualTo("${BOOTSTRAP_LIBRARIAN_EMAIL:}");
    }

    @Test
    void applicationYml_quandChargeBrut_alorsBootstrapPasswordADefautVide() throws IOException {
        // When
        Object raw = mainApplicationYaml().getProperty("app.bootstrap.librarian.password");

        // Then
        assertThat(raw).isEqualTo("${BOOTSTRAP_LIBRARIAN_PASSWORD:}");
    }

    @Test
    void ymlPrincipalEtProfils_quandProprieteSensible_alorsAucuneValeurParDefautNonVide() throws IOException {
        // Given : application.yml et tous les application-<profil>.yml de main
        List<Path> files;
        try (Stream<Path> found = Files.list(MAIN_RESOURCES)) {
            files = found.filter(p -> p.getFileName().toString().matches("application(-.+)?\\.ya?ml")).toList();
        }
        assertThat(files).isNotEmpty();

        // When : seul le nom de la propriété est rapporté, jamais la valeur
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            violations.addAll(violations(file.getFileName().toString(), Files.readString(file)));
        }

        // Then
        assertThat(violations).as("propriétés avec secret ou valeur par défaut interdite").isEmpty();
    }

    /**
     * Garde-fou : retourne {@code fichier : propriété} pour chaque propriété (a) au nom sensible avec un littéral ou
     * un défaut de placeholder non vide, ou (b) dont la valeur, quel que soit le nom, contient des identifiants d'URL.
     */
    static List<String> violations(String fileLabel, String yaml) throws IOException {
        List<String> violations = new ArrayList<>();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(fileLabel, new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        for (PropertySource<?> source : sources) {
            for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                String raw = String.valueOf(source.getProperty(name));
                boolean sensitive = SENSITIVE_NAME.matcher(name).find() && !isException(name);
                boolean flagged = (sensitive && (!stripPlaceholders(raw).isEmpty() || hasNonEmptyDefault(raw)))
                        || hasEmbeddedCredentials(raw);
                if (flagged) {
                    violations.add(fileLabel + " : " + name);
                }
            }
        }
        return violations;
    }

    private static boolean isException(String name) {
        return NON_SECRET_PROPERTIES.stream().anyMatch(e -> name.equals(e) || name.endsWith("." + e));
    }

    /** Retire les {@code ${...}} (imbriqués compris) : ce qui reste est du littéral. */
    private static String stripPlaceholders(String raw) {
        StringBuilder literal = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.startsWith("${", i)) {
                depth++;
                i++;
            } else if (depth > 0 && raw.charAt(i) == '}') {
                depth--;
            } else if (depth == 0) {
                literal.append(raw.charAt(i));
            }
        }
        return literal.toString();
    }

    /** Vrai si un placeholder (imbriqué ou non) a un défaut contenant du littéral : {@code ${A:${B:x}}} oui, {@code ${A:${B}}} non. */
    private static boolean hasNonEmptyDefault(String raw) {
        int from = raw.indexOf("${");
        while (from >= 0) {
            int depth = 1;
            int colon = -1;
            int i = from + 2;
            while (i < raw.length() && depth > 0) {
                if (raw.startsWith("${", i)) {
                    depth++;
                    i += 2;
                    continue;
                }
                char c = raw.charAt(i);
                if (c == '}') {
                    depth--;
                } else if (c == ':' && depth == 1 && colon < 0) {
                    colon = i;
                }
                i++;
            }
            int end = depth == 0 ? i - 1 : raw.length();
            if (colon >= 0) {
                String defaultValue = raw.substring(colon + 1, end);
                if (!stripPlaceholders(defaultValue).isEmpty() || hasNonEmptyDefault(defaultValue)) {
                    return true;
                }
            }
            from = raw.indexOf("${", end);
        }
        return false;
    }

    /** Identifiants littéraux dans une URL ; {@code ${NOM}} sans défaut est toléré (valeur lue dans l'environnement). */
    private static boolean hasEmbeddedCredentials(String raw) {
        Matcher url = URL_CREDENTIALS.matcher(raw);
        while (url.find()) {
            if (!BARE_PLACEHOLDER.matcher(url.group(1)).matches()
                    || !BARE_PLACEHOLDER.matcher(url.group(2)).matches()) {
                return true;
            }
        }
        Matcher parameter = PASSWORD_PARAMETER.matcher(raw);
        while (parameter.find()) {
            String value = parameter.group(1);
            if (!value.isEmpty() && !BARE_PLACEHOLDER.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    // --- Tests du garde-fou lui-même, sur des chaînes YAML en mémoire ---

    @Test
    void violations_quandNomSensibleAvecLitteral_alorsSignale() throws IOException {
        List<String> result = violations("t.yml", "app:\n  db-password: abc\n");

        assertThat(result).containsExactly("t.yml : app.db-password");
    }

    @Test
    void violations_quandNomSensibleAvecDefautDePlaceholder_alorsSignale() throws IOException {
        List<String> result = violations("t.yml", "app:\n  secret: ${X:abc}\n");

        assertThat(result).containsExactly("t.yml : app.secret");
    }

    @Test
    void violations_quandPlaceholderImbriqueAvecDefautLitteral_alorsSignale() throws IOException {
        List<String> result = violations("t.yml", "app:\n  secret: ${A:${B:abc}}\n");

        assertThat(result).containsExactly("t.yml : app.secret");
    }

    @Test
    void violations_quandPlaceholderImbriqueSansLitteral_alorsAccepte() throws IOException {
        List<String> result = violations("t.yml", "app:\n  secret: ${A:${B}}\n  other-secret: ${C:${D:}}\n");

        assertThat(result).isEmpty();
    }

    @Test
    void violations_quandNomSeTerminantParKey_alorsSignale() throws IOException {
        List<String> result = violations("t.yml", "app:\n  signing-key: abc\n");

        assertThat(result).containsExactly("t.yml : app.signing-key");
    }

    @Test
    void violations_quandNomCredential_alorsSignale() throws IOException {
        List<String> result = violations("t.yml", "app:\n  credentials: abc\n");

        assertThat(result).containsExactly("t.yml : app.credentials");
    }

    @Test
    void violations_quandIdentifiantsDansUneUrlSousNomNeutre_alorsSignale() throws IOException {
        List<String> result = violations("t.yml", "app:\n  endpoint: http://user:pass@host/x\n");

        assertThat(result).containsExactly("t.yml : app.endpoint");
    }

    @Test
    void violations_quandIdentifiantsDansLeDefautDUnPlaceholder_alorsSignale() throws IOException {
        List<String> result = violations("t.yml", "app:\n  endpoint: ${E:http://user:pass@host/x}\n");

        assertThat(result).containsExactly("t.yml : app.endpoint");
    }

    @Test
    void violations_quandPasswordOuPwdDansUneUrl_alorsSignale() throws IOException {
        List<String> result = violations(
                "t.yml", "app:\n  url-a: jdbc:x://h/db?user=a&password=zzz\n  url-b: jdbc:x://h/db;PWD=zzz\n");

        assertThat(result).containsExactlyInAnyOrder("t.yml : app.url-a", "t.yml : app.url-b");
    }

    @Test
    void violations_quandIdentifiantsUrlParPlaceholdersSansDefaut_alorsAccepte() throws IOException {
        List<String> result = violations(
                "t.yml", "app:\n  endpoint: http://${U}:${P}@host/x\n  url: jdbc:x://h/db?password=${P}\n");

        assertThat(result).isEmpty();
    }

    @Test
    void violations_quandNomSensibleDansLaListeDExceptions_alorsAccepte() throws IOException {
        List<String> result = violations("t.yml", "app:\n  token-ttl: PT1H\n  cookie-name: ACCESS_TOKEN\n");

        assertThat(result).isEmpty();
    }

    @Test
    void violations_quandNomProcheDUneExceptionMaisDifferent_alorsSignale() throws IOException {
        // l'exception porte sur token-ttl, pas sur tout nom contenant token
        List<String> result = violations("t.yml", "app:\n  token-value: abc\n");

        assertThat(result).containsExactly("t.yml : app.token-value");
    }

    @Test
    void applicationYml_quandChargeBrut_alorsImporteLeEnvDuModuleEtDeLaRacine() throws IOException {
        // When
        Object raw = mainApplicationYaml().getProperty("spring.config.import");

        // Then
        assertThat(String.valueOf(raw))
                .contains("optional:file:.env[.properties]")
                .contains("optional:file:../.env[.properties]");
    }
}
