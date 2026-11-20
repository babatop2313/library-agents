package com.example.library.config.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** RM-AUTH-12 : /dev/token n'existe qu'avec le profil local. */
class DevTokenProfileTest {

    private static final String BODY = "{\"roles\":[\"LIBRARIAN\"]}";

    @SpringBootTest
    @AutoConfigureMockMvc
    @Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
    @Nested
    class DefaultProfile {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void devToken_quandProfilParDefautSansJeton_alors401EtAucunJeton() throws Exception {
            // When / Then
            mockMvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.accessToken").doesNotExist());
        }

        @Test
        void devToken_quandProfilParDefautAvecJetonValide_alors404AucunJetonEmis() throws Exception {
            // When / Then : le contrôleur n'est pas déclaré hors profil local
            mockMvc.perform(post("/dev/token").header("Authorization", TestJwts.bearer(TestJwts.librarian()))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.accessToken").doesNotExist());
        }
    }

    @SpringBootTest
    @AutoConfigureMockMvc
    @Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
    @ActiveProfiles("local")
    @Nested
    class LocalProfile {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void devToken_quandProfilLocalEnGetSansJeton_alors401() throws Exception {
            // When / Then : seul POST /dev/token est public (plan), les autres méthodes exigent un jeton
            mockMvc.perform(get("/dev/token")).andExpect(status().isUnauthorized());
        }

        @Test
        void devToken_quandProfilLocal_alors200EtJetonEmis() throws Exception {
            // When / Then
            mockMvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.subject").isNotEmpty());
        }

        @Test
        void me_quandJetonDevTokenSansClaimEmail_alors200EmailNullEtSubjectBrut() throws Exception {
            // Given : le seul test qui passe par /dev/token, car c'est lui le sujet testé (décision 3)
            MvcResult token = mockMvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"subject\":\"sujet-brut\",\"roles\":[\"MEMBER\"]}"))
                    .andExpect(status().isOk()).andReturn();
            String jwt = com.jayway.jsonpath.JsonPath.read(token.getResponse().getContentAsString(), "$.accessToken");

            // When / Then
            mockMvc.perform(get("/api/v1/auth/me").header("Authorization", TestJwts.bearer(jwt)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value("sujet-brut"))
                    .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.roles[0]").value("MEMBER"));
        }
    }
}
