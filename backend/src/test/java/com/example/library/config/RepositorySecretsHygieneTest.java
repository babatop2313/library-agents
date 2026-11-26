package com.example.library.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Hygiène des secrets au niveau du dépôt : les fichiers .env ne sont ni ignorés par erreur ni suivis,
 * et le modèle .env.example documente chaque variable lue. Le contenu d'un vrai .env n'est jamais lu.
 */
class RepositorySecretsHygieneTest {

    /** Racine du dépôt : premier dossier contenant .git en remontant du dossier courant (backend/ ou racine). */
    private static final Path ROOT = findRepositoryRoot();
    private static final Path RESOURCES = ROOT == null ? null : ROOT.resolve("backend/src/main/resources");
    private static final Pattern YAML_FILE = Pattern.compile("application(-.+)?\\.ya?ml");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(?::([^}]*))?}");
    /** Variables qui portent un secret ou une donnée personnelle : déclarées sans valeur dans le modèle. */
    private static final Pattern SENSITIVE = Pattern.compile("SECRET|PASSWORD|USER|EMAIL|TOKEN|KEY");

    private static Path findRepositoryRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) {
                return dir;
            }
        }
        return null;
    }

    /** Hors CI : test ignoré si git ou le dépôt manque. En CI (variable CI définie) : échec, jamais de faux vert. */
    @BeforeAll
    static void gitDisponible() {
        boolean available = ROOT != null && git("--version") == 0 && git("rev-parse", "--is-inside-work-tree") == 0;
        if (!available && System.getenv("CI") != null) {
            fail("git ou le dépôt est indisponible en CI : les contrôles d'hygiène des secrets ne peuvent pas s'exécuter");
        }
        assumeTrue(available, "git ou dépôt indisponible : tests de dépôt ignorés");
    }

    private static int git(String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", ROOT.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** {@code --no-index} : évalue la règle d'ignore même pour un fichier déjà suivi. */
    private static boolean ignored(String path) {
        return git("check-ignore", "-q", "--no-index", path) == 0;
    }

    private static boolean tracked(String path) {
        return git("ls-files", "--error-unmatch", path) == 0;
    }

    @Test
    void gitignore_quandEnvALaRacine_alorsIgnore() {
        assertThat(ignored(".env")).isTrue();
    }

    @Test
    void gitignore_quandEnvDuBackend_alorsIgnore() {
        assertThat(ignored("backend/.env")).isTrue();
    }

    @Test
    void gitignore_quandVarianteEnvLocal_alorsIgnoree() {
        assertThat(ignored(".env.local")).isTrue();
        assertThat(ignored("backend/.env.local")).isTrue();
    }

    @Test
    void gitignore_quandModeleEnvExample_alorsNonIgnore() {
        assertThat(ignored(".env.example")).isFalse();
    }

    @Test
    void depot_quandEnvALaRacine_alorsNonSuivi() {
        assertThat(tracked(".env")).as(".env suivi par git").isFalse();
    }

    @Test
    void depot_quandEnvDuBackend_alorsNonSuivi() {
        assertThat(tracked("backend/.env")).as("backend/.env suivi par git").isFalse();
    }

    @Test
    void envExample_quandDepotCharge_alorsExisteEtEstSuivi() {
        assertThat(ROOT.resolve(".env.example")).exists();
        assertThat(tracked(".env.example")).isTrue();
    }

    @Test
    void envExample_quandVariablesLuesDansLesYml_alorsChaqueVariableSensibleOuObligatoireEstDeclaree()
            throws IOException {
        // Given : les variables ${NOM...} de tous les application*.yml. Sont à documenter : les variables sensibles et celles sans
        // défaut (obligatoires). Les réglages techniques à défaut non vide non sensible (DB_URL, REDIS_HOST,
        // REDIS_PORT, REDIS_SSL, JWT_TTL) restent facultatifs : le code fournit une valeur sûre.
        Set<String> required = new LinkedHashSet<>();
        List<Path> ymls;
        try (Stream<Path> found = Files.list(RESOURCES)) {
            ymls = found.filter(p -> YAML_FILE.matcher(p.getFileName().toString()).matches())
                    .toList();
        }
        assertThat(ymls).isNotEmpty();
        for (Path yml : ymls) {
            for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
                if (line.stripLeading().startsWith("#")) {
                    continue;
                }
                Matcher m = VARIABLE.matcher(line);
                while (m.find()) {
                    if (m.group(2) == null || SENSITIVE.matcher(m.group(1)).find()) {
                        required.add(m.group(1));
                    }
                }
            }
        }
        assertThat(required).contains("DB_USER", "DB_PASSWORD", "JWT_SECRET");

        // When : lecture du seul modèle
        Path example = ROOT.resolve(".env.example");
        assertThat(example).exists();
        List<String> lines = Files.readAllLines(example, StandardCharsets.UTF_8);

        // Then : ligne NOM=..., et strictement NOM= pour les variables sensibles
        List<String> problems = new ArrayList<>();
        for (String name : required) {
            String declaration =
                    lines.stream().filter(l -> l.startsWith(name + "=")).findFirst().orElse(null);
            if (declaration == null) {
                problems.add(name + " non déclarée");
            } else if (SENSITIVE.matcher(name).find() && !declaration.equals(name + "=")) {
                problems.add(name + " porte une valeur dans le modèle");
            }
        }
        assertThat(problems).isEmpty();
    }
}
