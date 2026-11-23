package com.example.library.identity.adapter.in.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration;
import com.example.library.identity.application.port.in.BootstrapLibrarianUseCase;
import com.example.library.identity.application.port.out.PasswordHasher;
import com.example.library.identity.config.LibrarianBootstrapProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RM-AUTH-08 : amorçage du premier bibliothécaire avec PostgreSQL réel. Un seul contexte (variables d'amorçage
 * valides) pour tous les scénarios : le démarrage réel est photographié par un écouteur d'{@code ApplicationReadyEvent},
 * les autres scénarios rejouent le runner avec d'autres valeurs sur la même base, nettoyée avant chaque test.
 */
@SpringBootTest(properties = {
        "app.bootstrap.librarian.email= Admin@Example.org ",
        "app.bootstrap.librarian.password=Bootstrap-Passw0rd!",
        "app.security.bcrypt.strength=4"})
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class,
        LibrarianBootstrapIntegrationTest.StartupSnapshotConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class LibrarianBootstrapIntegrationTest {

    private static final String PASSWORD = "Bootstrap-Passw0rd!";

    /** Ce que le démarrage réel a laissé en base, relevé avant tout test. */
    record Snapshot(List<Map<String, Object>> users, List<String> roles) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StartupSnapshotConfig {

        static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>();

        @Bean
        StartupSnapshotListener startupSnapshotListener(JdbcTemplate jdbc) {
            return new StartupSnapshotListener(jdbc);
        }
    }

    static class StartupSnapshotListener {

        private final JdbcTemplate jdbc;

        StartupSnapshotListener(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @EventListener(ApplicationReadyEvent.class)
        void onReady() {
            StartupSnapshotConfig.SNAPSHOT.set(new Snapshot(
                    jdbc.queryForList("SELECT id, email, enabled, password_hash FROM app_user"),
                    jdbc.queryForList("SELECT role FROM user_roles", String.class)));
        }
    }

    @Autowired
    private BootstrapLibrarianUseCase useCase;

    @Autowired
    private LibrarianBootstrapProperties properties;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    @AfterEach
    void cleanUsers() {
        jdbc.update("DELETE FROM app_user");
    }

    private void runWith(String email, String password) {
        new LibrarianBootstrapRunner(useCase, new LibrarianBootstrapProperties(email, password))
                .run(new DefaultApplicationArguments());
    }

    private int userCount() {
        return jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class);
    }

    private void givenMember(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, email, password_hash, enabled, created_at) VALUES (?, ?, ?, TRUE, ?)",
                id, email, passwordHasher.hash("Member-Passw0rd!!").value(), OffsetDateTime.now(ZoneOffset.UTC));
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'MEMBER')", id);
    }

    @Test
    void demarrage_quandAucunBibliothecaireEtVariablesValides_alorsCreeLibrarianSeulActifNormaliseEtHache() {
        // Given : le contexte a démarré avec email=" Admin@Example.org " (voir @SpringBootTest)
        Snapshot snapshot = StartupSnapshotConfig.SNAPSHOT.get();

        // When / Then
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.users()).hasSize(1);
        Map<String, Object> user = snapshot.users().get(0);
        assertThat(user.get("email")).isEqualTo("admin@example.org");
        assertThat(user.get("enabled")).isEqualTo(true);
        assertThat((String) user.get("password_hash")).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(snapshot.roles()).containsExactly("LIBRARIAN");
    }

    @Test
    void runner_quandAucunBibliothecaire_alorsLeBibliothecaireCreeSePeutConnecter() throws Exception {
        // Given
        assertThat(userCount()).isZero();

        // When : même chemin que le démarrage, avec les propriétés réelles du contexte
        runWith(properties.email(), properties.password());

        // Then
        assertThat(userCount()).isEqualTo(1);
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.org\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("LIBRARIAN"))
                .andExpect(jsonPath("$.roles.length()").value(1));
    }

    @Test
    void runner_quandBibliothecaireDejaPresentEtVariablesDifferentes_alorsIdempotentRienDeModifie() {
        // Given
        runWith(properties.email(), properties.password());
        Map<String, Object> before = jdbc.queryForMap("SELECT id, email, password_hash, created_at FROM app_user");

        // When
        runWith("autre@example.org", "Une-Autre-Passw0rd!");

        // Then
        assertThat(userCount()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT id, email, password_hash, created_at FROM app_user")).isEqualTo(before);
    }

    @Test
    void runner_quandVariablesAbsentes_alorsAucunUtilisateurEtWarn(CapturedOutput output) {
        // When
        runWith(null, null);
        runWith("", "");

        // Then
        assertThat(userCount()).isZero();
        assertThat(output.getAll()).contains("BOOTSTRAP_LIBRARIAN_EMAIL ou BOOTSTRAP_LIBRARIAN_PASSWORD absent");
    }

    @Test
    void runner_quandMotDePasseDe11Caracteres_alorsAucunUtilisateurWarnEtMotDePasseAbsentDesLogs(
            CapturedOutput output) {
        // Given
        String weak = "Abcdefg-123";
        assertThat(weak).hasSize(11);

        // When
        runWith("admin@example.org", weak);

        // Then
        assertThat(userCount()).isZero();
        assertThat(output.getAll()).contains("BOOTSTRAP_LIBRARIAN_PASSWORD ne respecte pas la politique")
                .doesNotContain(weak);
    }

    @Test
    void runner_quandEmailMalForme_alorsAucunUtilisateurEtWarn(CapturedOutput output) {
        // When
        runWith("pas-un-email", PASSWORD);

        // Then
        assertThat(userCount()).isZero();
        assertThat(output.getAll()).contains("n'est pas une adresse e-mail valide").doesNotContain(PASSWORD);
    }

    @Test
    void runner_quandEmailDUnMemberExistant_alorsInchangeAucunCreeWarnAvecEmailMasque(CapturedOutput output) {
        // Given
        givenMember("admin@example.org");
        Map<String, Object> before = jdbc.queryForMap("SELECT id, email, password_hash, enabled FROM app_user");

        // When
        runWith("admin@example.org", PASSWORD);

        // Then
        assertThat(userCount()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT id, email, password_hash, enabled FROM app_user")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT role FROM user_roles", String.class)).containsExactly("MEMBER");
        assertThat(output.getAll()).contains("a***@example.org").doesNotContain(PASSWORD);
    }

    @Test
    void runner_quandSuccesEtEchecs_alorsLeMotDePasseDAmorcageNApparaitDansAucunLog(CapturedOutput output) {
        // When
        runWith(properties.email(), properties.password());
        runWith(properties.email(), properties.password());
        runWith("admin@example.org", "Xy7-zzqq");

        // Then
        assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain("Xy7-zzqq");
    }

    @Test
    void runner_quandSucces_alorsInfoAvecUserIdSansEmail(CapturedOutput output) {
        // When
        runWith(properties.email(), properties.password());

        // Then
        String id = jdbc.queryForObject("SELECT id FROM app_user", String.class);
        assertThat(output.getAll()).contains("Bibliothécaire d'amorçage créé : userId=" + id);
    }
}
