package com.example.library.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration;
import com.example.library.identity.application.port.in.BootstrapDevMemberCommand;
import com.example.library.identity.application.port.in.BootstrapDevMemberUseCase;
import com.example.library.identity.application.port.in.DevMemberBootstrapResult;
import com.example.library.identity.domain.model.DevMemberBootstrapOutcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RM-LOAN-30 avec PostgreSQL réel : membre de test créé au démarrage en profil local seulement, idempotent,
 * connexion possible, mot de passe absent des journaux.
 */
class DevMemberBootstrapIntegrationTest {

    private static final String EMAIL = "membre.local@example.org";
    private static final String PASSWORD = "Dev-Member-Passw0rd!";

    @SpringBootTest(properties = {
            "app.dev.member.email=" + EMAIL,
            "app.dev.member.password=" + PASSWORD,
            "app.security.bcrypt.strength=4"})
    @AutoConfigureMockMvc
    @Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
    @ActiveProfiles("local")
    @ExtendWith(OutputCaptureExtension.class)
    @Nested
    class LocalProfile {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private JdbcTemplate jdbc;

        @Autowired
        private BootstrapDevMemberUseCase useCase;

        @Test
        void demarrage_quandProfilLocalEtVariablesValides_alorsMembreMemberSeulActifCreeEtHache() {
            // Given : le contexte a démarré avec les variables du membre de test
            // When
            List<String> roles = jdbc.queryForList(
                    "SELECT r.role FROM user_roles r JOIN app_user u ON u.id = r.user_id WHERE u.email = ?",
                    String.class, EMAIL);
            Boolean enabled = jdbc.queryForObject("SELECT enabled FROM app_user WHERE email = ?", Boolean.class, EMAIL);
            String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email = ?", String.class, EMAIL);
            // Then
            assertThat(roles).containsExactly("MEMBER");
            assertThat(enabled).isTrue();
            assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
        }

        @Test
        void login_quandMembreDeTest_alors200AvecRoleMember() throws Exception {
            // When / Then
            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles[0]").value("MEMBER"));
        }

        @Test
        void bootstrap_quandRedemarrage_alorsAlreadyPresentEtUnSeulUtilisateur(CapturedOutput output) {
            // When : second démarrage simulé
            DevMemberBootstrapResult result = useCase.bootstrap(new BootstrapDevMemberCommand(EMAIL, PASSWORD));
            // Then : rien n'est créé ni modifié, et le mot de passe n'apparaît dans aucun journal
            assertThat(result.outcome()).isEqualTo(DevMemberBootstrapOutcome.ALREADY_PRESENT);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, EMAIL))
                    .isEqualTo(1);
            assertThat(output.getAll()).doesNotContain(PASSWORD);
        }
    }

    @SpringBootTest(properties = {
            "app.dev.member.email=" + EMAIL,
            "app.dev.member.password=" + PASSWORD})
    @Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
    @Nested
    class DefaultProfile {

        @Autowired
        private JdbcTemplate jdbc;

        @Autowired
        private ApplicationContext context;

        @Test
        void demarrage_quandProfilParDefautAvecVariablesDefinies_alorsAucunMembreDeTestCree() {
            // Given : les variables sont définies mais le profil local est absent
            // When / Then
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, EMAIL))
                    .isZero();
            assertThat(context.containsBean("devMemberBootstrapRunner")).isFalse();
        }
    }
}
