package com.example.library.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration;
import com.example.library.config.security.TestJwts;
import com.example.library.identity.application.port.out.PasswordHasher;
import com.example.library.identity.domain.model.PasswordHash;
import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Parcours complet avec PostgreSQL et Redis réels : connexion, cookie, CSRF, rôles, routes protégées
 * (RM-AUTH-04 à 07, 10, 23 à 28). Les utilisateurs sont créés directement en base ; les JWT « Bearer »
 * sont forgés par {@code TestJwts}, jamais par /dev/token (décision 14).
 */
@SpringBootTest(properties = "app.security.bcrypt.strength=4")
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
class AuthenticationFlowIntegrationTest {

    private static final String PASSWORD = "S3cret!pass-long";
    private static final String BOOK = "{\"isbn\":\"9782070368228\",\"title\":\"L'Etranger\","
            + "\"author\":\"Albert Camus\",\"copies\":3}";
    private static final String BOOK_2 = "{\"isbn\":\"9780306406157\",\"title\":\"Autre livre\","
            + "\"author\":\"Auteur\",\"copies\":1}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private org.springframework.security.web.FilterChainProxy filterChainProxy;

    @MockitoSpyBean
    private PasswordHasher passwordHasher;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM book");
        jdbc.update("DELETE FROM app_user");
    }

    // ------------------------------------------------------------------ aides

    private UUID givenUser(String email, boolean enabled, String... roles) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, email, password_hash, enabled, created_at) VALUES (?, ?, ?, ?, ?)",
                id, email, passwordHasher.hash(PASSWORD).value(), enabled, OffsetDateTime.now(ZoneOffset.UTC));
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        }
        clearInvocations(passwordHasher);
        return id;
    }

    private ResultActions login(String email, String password) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String cookieValue(MvcResult result, String name) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(name.length() + 1, c.indexOf(';')))
                .findFirst().orElseThrow(() -> new AssertionError("cookie absent : " + name));
    }

    /**
     * Session de navigateur : cookie ACCESS_TOKEN de la connexion ; le jeton CSRF est obtenu comme le fait le
     * frontend au rechargement, par un GET /me (la réponse du login ne le pose pas : voir le test dédié).
     */
    private MockHttpServletRequestBuilder withBrowserSession(MockHttpServletRequestBuilder request, MvcResult login,
                                                             boolean withXsrfHeader) throws Exception {
        Cookie accessToken = new Cookie("ACCESS_TOKEN", cookieValue(login, "ACCESS_TOKEN"));
        MvcResult me = mockMvc.perform(get("/api/v1/auth/me").cookie(accessToken)).andExpect(status().isOk())
                .andReturn();
        String xsrf = cookieValue(me, "XSRF-TOKEN");
        request.cookie(accessToken, new Cookie("XSRF-TOKEN", xsrf));
        if (withXsrfHeader) {
            request.header("X-XSRF-TOKEN", xsrf);
        }
        return request;
    }

    // ------------------------------------------------------------------ connexion et cookie

    @Test
    void login_quandMembreActif_alorsCookieJwtHs256ClaimsConformesEtCorpsSansJeton() throws Exception {
        // Given
        UUID id = givenUser("alice@example.org", true, "MEMBER");

        // When
        MvcResult result = login("alice@example.org", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(id.toString()))
                .andExpect(jsonPath("$.email").value("alice@example.org"))
                .andExpect(jsonPath("$.roles[0]").value("MEMBER"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();

        // Then
        String setCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(c -> c.startsWith("ACCESS_TOKEN=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/api").contains("Max-Age=3600");
        String token = cookieValue(result, "ACCESS_TOKEN");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
        Jwt jwt = jwtDecoder.decode(token);
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getSubject()).isEqualTo(id.toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo("alice@example.org");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("MEMBER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("library");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(1));
        assertThat(jwt.getClaims().keySet()).doesNotContain("password", "passwordHash", "password_hash");
    }

    @Test
    void login_quandSucces_alorsCookieXsrfTokenPoseParLaReponseDeConnexion() throws Exception {
        // Given
        givenUser("alice@example.org", true, "MEMBER");

        // When
        MvcResult result = login("alice@example.org", PASSWORD).andExpect(status().isOk()).andReturn();

        // Then : la spec (contrat de POST /login) exige ACCESS_TOKEN et XSRF-TOKEN dans la même réponse
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .anyMatch(c -> c.startsWith("ACCESS_TOKEN="))
                .anyMatch(c -> c.startsWith("XSRF-TOKEN="));
    }

    @Test
    void me_quandCookieDeLaConnexion_alors200AvecEmailEtRoles() throws Exception {
        // Given
        givenUser("alice@example.org", true, "MEMBER");
        MvcResult login = login("alice@example.org", PASSWORD).andExpect(status().isOk()).andReturn();

        // When / Then
        mockMvc.perform(withBrowserSession(get("/api/v1/auth/me"), login, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.org"))
                .andExpect(jsonPath("$.roles[0]").value("MEMBER"));
    }

    @Test
    void login_quandRolesCumules_alorsLesDeuxRolesDansLeCorpsEtLeJwt() throws Exception {
        // Given
        givenUser("both@example.org", true, "MEMBER", "LIBRARIAN");

        // When
        MvcResult result = login("both@example.org", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("LIBRARIAN"))
                .andExpect(jsonPath("$.roles[1]").value("MEMBER"))
                .andReturn();

        // Then
        Jwt jwt = jwtDecoder.decode(cookieValue(result, "ACCESS_TOKEN"));
        assertThat(jwt.getClaimAsStringList("roles")).containsExactlyInAnyOrder("MEMBER", "LIBRARIAN");
    }

    @Test
    void login_quandEmailEnMajusculesEtEspaces_alors200() throws Exception {
        // Given
        givenUser("alice@example.org", true, "MEMBER");

        // When / Then
        login("  Alice@Example.ORG  ", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.org"));
    }

    @Test
    void login_quandMotDePasseStocke_alorsHashBcryptSansTexteEnClair() throws Exception {
        // Given
        givenUser("alice@example.org", true, "MEMBER");

        // When
        String stored = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email = 'alice@example.org'",
                String.class);

        // Then
        assertThat(stored).startsWith("$2").doesNotContain(PASSWORD);
    }

    // ------------------------------------------------------------------ échecs d'identification (RM-AUTH-07)

    @Test
    void login_quandMotDePasseErrone_alors401InvalidCredentialsSansCookie() throws Exception {
        // Given
        givenUser("alice@example.org", true, "MEMBER");

        // When
        MvcResult result = login("alice@example.org", "mauvais-mot-de-passe")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn();

        // Then
        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("ACCESS_TOKEN="));
    }

    @Test
    void login_quandEmailInconnu_alorsMemeReponseQueMotDePasseErroneEtBcryptExecute() throws Exception {
        // Given
        givenUser("alice@example.org", true, "MEMBER");
        String wrongPassword = login("alice@example.org", "mauvais-mot-de-passe")
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        PasswordHash dummy = passwordHasher.dummyHash();
        clearInvocations(passwordHasher);

        // When
        String unknown = login("inconnu@example.org", "mauvais-mot-de-passe")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();

        // Then : même corps, et une comparaison BCrypt a eu lieu contre le hash factice
        assertThat(unknown).isEqualTo(wrongPassword);
        verify(passwordHasher, times(1)).matches(any(), eq(dummy));
    }

    @Test
    void login_quandCompteDesactive_alorsMemeReponse401() throws Exception {
        // Given
        givenUser("off@example.org", false, "MEMBER");

        // When / Then
        login("off@example.org", PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("E-mail ou mot de passe incorrect"));
    }

    @Test
    void login_quandMotDePasseDe73Octets_alors401InvalidCredentials() throws Exception {
        // Given
        givenUser("alice@example.org", true, "MEMBER");

        // When / Then
        login("alice@example.org", "a".repeat(73)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ------------------------------------------------------------------ rôles et CSRF sur une route protégée

    @Test
    void creationLivre_quandBibliothecaireParCookieAvecXsrf_alors201() throws Exception {
        // Given
        givenUser("lib@example.org", true, "LIBRARIAN");
        MvcResult login = login("lib@example.org", PASSWORD).andExpect(status().isOk()).andReturn();

        // When / Then
        mockMvc.perform(withBrowserSession(post("/api/v1/books"), login, true)
                        .contentType(MediaType.APPLICATION_JSON).content(BOOK))
                .andExpect(status().isCreated());
    }

    @Test
    void creationLivre_quandBibliothecaireParCookieSansXsrf_alors403ProblemDetail() throws Exception {
        // Given
        givenUser("lib@example.org", true, "LIBRARIAN");
        MvcResult login = login("lib@example.org", PASSWORD).andExpect(status().isOk()).andReturn();

        // When / Then
        mockMvc.perform(withBrowserSession(post("/api/v1/books"), login, false)
                        .contentType(MediaType.APPLICATION_JSON).content(BOOK))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void creationLivre_quandCookieAvecEnTeteXsrfIncorrect_alors403() throws Exception {
        // Given
        givenUser("lib@example.org", true, "LIBRARIAN");
        MvcResult login = login("lib@example.org", PASSWORD).andExpect(status().isOk()).andReturn();

        // When / Then
        mockMvc.perform(withBrowserSession(post("/api/v1/books"), login, false).header("X-XSRF-TOKEN", "incorrect")
                        .contentType(MediaType.APPLICATION_JSON).content(BOOK))
                .andExpect(status().isForbidden());
    }

    @Test
    void creationLivre_quandXsrfObtenuParLaReponseDeLogin_alors201SansGetMe() throws Exception {
        // Given : aucun GET intermédiaire, le jeton CSRF vient uniquement de la réponse du login
        givenUser("lib@example.org", true, "LIBRARIAN");
        MvcResult login = login("lib@example.org", PASSWORD).andExpect(status().isOk()).andReturn();
        String xsrf = cookieValue(login, "XSRF-TOKEN");

        // When / Then
        mockMvc.perform(post("/api/v1/books")
                        .cookie(new Cookie("ACCESS_TOKEN", cookieValue(login, "ACCESS_TOKEN")),
                                new Cookie("XSRF-TOKEN", xsrf))
                        .header("X-XSRF-TOKEN", xsrf)
                        .contentType(MediaType.APPLICATION_JSON).content(BOOK))
                .andExpect(status().isCreated());
    }

    @Test
    void methodesModifiantes_quandCookieSansXsrf_alors403AvantTouteResolutionDeRoute() throws Exception {
        // Given
        givenUser("lib@example.org", true, "LIBRARIAN");
        MvcResult login = login("lib@example.org", PASSWORD).andExpect(status().isOk()).andReturn();
        Cookie token = new Cookie("ACCESS_TOKEN", cookieValue(login, "ACCESS_TOKEN"));

        // When / Then
        mockMvc.perform(put("/api/v1/books/1").cookie(token)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/books/1").cookie(token)).andExpect(status().isForbidden());
    }

    @Test
    void creationLivre_quandCookieValideEtAuthorizationBasicSansXsrf_alors401EtAucunLivreCree() throws Exception {
        // When / Then : l'en-tête non Bearer dispense de CSRF mais ne permet pas l'authentification par cookie : 401
        mockMvc.perform(post("/api/v1/books").cookie(new Cookie("ACCESS_TOKEN", TestJwts.librarian()))
                        .header("Authorization", "Basic Zm9vOmJhcg==")
                        .contentType(MediaType.APPLICATION_JSON).content(BOOK))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM book", Integer.class)).isZero();
    }

    @Test
    void creationLivre_quandCookieValideEtAuthorizationVideSansXsrf_alors401EtAucunLivreCree() throws Exception {
        // When / Then
        mockMvc.perform(post("/api/v1/books").cookie(new Cookie("ACCESS_TOKEN", TestJwts.librarian()))
                        .header("Authorization", "")
                        .contentType(MediaType.APPLICATION_JSON).content(BOOK))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM book", Integer.class)).isZero();
    }

    @Test
    void me_quandCookieValideEtAuthorizationBasic_alors401() throws Exception {
        // When / Then
        mockMvc.perform(get("/api/v1/auth/me").cookie(new Cookie("ACCESS_TOKEN", TestJwts.member()))
                        .header("Authorization", "Basic Zm9vOmJhcg=="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void filterChain_quandConstruite_alorsCsrfCookieFilterEntreCsrfFilterEtBearerTokenAuthenticationFilter() {
        // Given : la chaîne qui sert l'API (une autre chaîne, ex. profil mcp-local, peut la précéder)
        MockHttpServletRequest apiRequest = new MockHttpServletRequest("POST", "/api/v1/books");
        SecurityFilterChain apiChain = filterChainProxy.getFilterChains().stream()
                .filter(chain -> chain.matches(apiRequest))
                .findFirst()
                .orElseThrow();

        // When
        List<String> filters = apiChain.getFilters().stream().map(f -> f.getClass().getSimpleName()).toList();

        // Then
        int csrf = filters.indexOf("CsrfFilter");
        int cookie = filters.indexOf("CsrfCookieFilter");
        int bearer = filters.indexOf("BearerTokenAuthenticationFilter");
        assertThat(csrf).isNotNegative();
        assertThat(cookie).isGreaterThan(csrf);
        assertThat(bearer).isGreaterThan(cookie);
    }

    @Test
    void creationLivre_quandBearerLibrarianSansXsrf_alors201() throws Exception {
        // When / Then
        mockMvc.perform(post("/api/v1/books").header("Authorization", TestJwts.bearer(TestJwts.librarian()))
                        .contentType(MediaType.APPLICATION_JSON).content(BOOK_2))
                .andExpect(status().isCreated());
    }

    @Test
    void creationLivre_quandMembre_alors403() throws Exception {
        // Given
        givenUser("alice@example.org", true, "MEMBER");
        MvcResult login = login("alice@example.org", PASSWORD).andExpect(status().isOk()).andReturn();

        // When / Then : cookie + XSRF valides mais rôle insuffisant
        mockMvc.perform(withBrowserSession(post("/api/v1/books"), login, true)
                        .contentType(MediaType.APPLICATION_JSON).content(BOOK))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/books").header("Authorization", TestJwts.bearer(TestJwts.member()))
                        .contentType(MediaType.APPLICATION_JSON).content(BOOK))
                .andExpect(status().isForbidden());
    }

    @Test
    void creationLivre_quandUtilisateurAvecRolesCumules_alors201() throws Exception {
        // Given
        givenUser("both@example.org", true, "MEMBER", "LIBRARIAN");
        MvcResult login = login("both@example.org", PASSWORD).andExpect(status().isOk()).andReturn();

        // When / Then
        mockMvc.perform(withBrowserSession(post("/api/v1/books"), login, true)
                        .contentType(MediaType.APPLICATION_JSON).content(BOOK))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ déconnexion, routes publiques

    @Test
    void logout_quandCookieAvecXsrfValide_alors204EtCookieVide() throws Exception {
        // Given
        givenUser("alice@example.org", true, "MEMBER");
        MvcResult login = login("alice@example.org", PASSWORD).andExpect(status().isOk()).andReturn();

        // When
        MvcResult result = mockMvc.perform(withBrowserSession(post("/api/v1/auth/logout"), login, true))
                .andExpect(status().isNoContent()).andReturn();

        // Then
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .anyMatch(c -> c.startsWith("ACCESS_TOKEN=;") && c.contains("Max-Age=0") && c.contains("HttpOnly")
                        && c.contains("Secure") && c.contains("SameSite=Strict") && c.contains("Path=/api"));
    }

    @Test
    void logout_quandCookieSansXsrf_alors403() throws Exception {
        // Given
        givenUser("alice@example.org", true, "MEMBER");
        MvcResult login = login("alice@example.org", PASSWORD).andExpect(status().isOk()).andReturn();

        // When / Then
        mockMvc.perform(withBrowserSession(post("/api/v1/auth/logout"), login, false))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void me_quandSansAuthentification_alors401ProblemDetail() throws Exception {
        // When / Then
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void me_quandJetonExpire_alors401() throws Exception {
        // When / Then
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", TestJwts.bearer(TestJwts.expired())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(new Cookie("ACCESS_TOKEN", TestJwts.expired())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_quandCookieExpirePresent_alorsRoutePublique200() throws Exception {
        // Given
        givenUser("alice@example.org", true, "MEMBER");
        String body = "{\"email\":\"alice@example.org\",\"password\":\"" + PASSWORD + "\"}";

        // When / Then : ni 401 du resource server, ni 403 CSRF
        mockMvc.perform(post("/api/v1/auth/login").cookie(new Cookie("ACCESS_TOKEN", TestJwts.expired()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void books_quandAnonyme_alors401() throws Exception {
        // When / Then
        mockMvc.perform(get("/api/v1/books")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void error_quandRepartitionErreurSansAuthentification_alorsAccessibleMaisPasEnRequeteDirecte() throws Exception {
        // When / Then : une requête directe sur /error est protégée...
        mockMvc.perform(get("/error")).andExpect(status().isUnauthorized());

        // ... mais la répartition d'erreur du conteneur (DispatcherType.ERROR) est ouverte (RM-AUTH-28)
        mockMvc.perform(get("/error").with(request -> {
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute("jakarta.servlet.error.status_code", 404);
            return request;
        })).andExpect(status().isNotFound());
    }

    @Test
    void actuatorHealth_quandAnonyme_alorsPublic() throws Exception {
        // When / Then
        mockMvc.perform(get("/actuator/health")).andExpect(status().is2xxSuccessful());
    }

}
