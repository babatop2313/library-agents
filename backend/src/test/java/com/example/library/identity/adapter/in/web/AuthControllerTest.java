package com.example.library.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.library.config.security.SecurityConfig;
import com.example.library.config.security.TestJwts;
import com.example.library.identity.application.port.in.CurrentUser;
import com.example.library.identity.application.port.in.GetCurrentUserQuery;
import com.example.library.identity.application.port.in.GetCurrentUserUseCase;
import com.example.library.identity.application.port.in.LoginCommand;
import com.example.library.identity.application.port.in.LoginResult;
import com.example.library.identity.application.port.in.LoginUseCase;
import com.example.library.identity.domain.exception.InvalidCredentialsException;
import com.example.library.identity.domain.model.AccessToken;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.UserId;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Contrat HTTP de /api/v1/auth (RM-AUTH-05, 07, 09, 10, 23 à 28). Use cases simulés. */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, AccessTokenCookies.class})
class AuthControllerTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ME = "/api/v1/auth/me";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-20T11:00:00Z");
    private static final String JWT_IN_COOKIE = "header.payload.signature-secret-jwt";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoginUseCase loginUseCase;

    @MockitoBean
    private GetCurrentUserUseCase getCurrentUserUseCase;

    private UserId userId;

    @BeforeEach
    void givenSuccessfulLogin() {
        userId = new UserId(UUID.randomUUID());
        when(loginUseCase.login(any())).thenReturn(new LoginResult(userId, Email.of("alice@example.org"),
                Set.of(Role.MEMBER), new AccessToken(JWT_IN_COOKIE, EXPIRES_AT)));
    }

    private ResultActions login(String body) throws Exception {
        return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String credentials(String email, String password) {
        return "{\"email\":" + (email == null ? "null" : "\"" + email + "\"")
                + ",\"password\":" + (password == null ? "null" : "\"" + password + "\"") + "}";
    }

    private ResultActions assertValidation(ResultActions result, String field, String code) throws Exception {
        return result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    // ---------------------------------------------------------------- login : succès

    @Test
    void login_quandIdentifiantsValides_alorsCookieHttpOnlySecureStrictPathApiMaxAge3600() throws Exception {
        // Given : use case simulé (voir @BeforeEach)

        // When
        MvcResult result = login(credentials("alice@example.org", "S3cret!pass-long"))
                .andExpect(status().isOk()).andReturn();

        // Then
        List<String> cookies = result.getResponse().getHeaders("Set-Cookie");
        String accessToken = cookies.stream().filter(c -> c.startsWith("ACCESS_TOKEN=")).findFirst().orElseThrow();
        assertThat(accessToken).startsWith("ACCESS_TOKEN=" + JWT_IN_COOKIE + ";")
                .contains("Path=/api").contains("Max-Age=3600").contains("Secure")
                .contains("HttpOnly").contains("SameSite=Strict");
    }

    @Test
    void login_quandSucces_alorsCorpsSansJetonAvecIdentiteEtExpiration() throws Exception {
        // When
        MvcResult result = login(credentials("alice@example.org", "S3cret!pass-long"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.value().toString()))
                .andExpect(jsonPath("$.email").value("alice@example.org"))
                .andExpect(jsonPath("$.roles[0]").value("MEMBER"))
                .andExpect(jsonPath("$.roles", hasSize(1)))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-20T11:00:00Z"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.jwt").doesNotExist())
                .andReturn();

        // Then
        assertThat(result.getResponse().getContentAsString()).doesNotContain(JWT_IN_COOKIE);
    }

    @Test
    void login_quandSucces_alorsCookieXsrfTokenPoseSansHttpOnly() throws Exception {
        // When
        MvcResult result = login(credentials("alice@example.org", "S3cret!pass-long"))
                .andExpect(status().isOk()).andReturn();

        // Then : le contrat de POST /login (spec) pose ACCESS_TOKEN et XSRF-TOKEN dans la même réponse
        List<String> cookies = result.getResponse().getHeaders("Set-Cookie");
        assertThat(cookies).anyMatch(c -> c.startsWith("XSRF-TOKEN="));
        assertThat(cookies).filteredOn(c -> c.startsWith("XSRF-TOKEN=")).noneMatch(c -> c.contains("HttpOnly"));
    }

    @Test
    void login_quandSucces_alorsCookieXsrfTokenSecureEtSameSiteStrict() throws Exception {
        // When
        MvcResult result = login(credentials("alice@example.org", "S3cret!pass-long"))
                .andExpect(status().isOk()).andReturn();

        // Then : mêmes protections que le cookie du jeton, hors HttpOnly. Spring Security pose SameSite comme
        // attribut du Cookie (Servlet 6), que le serveur écrit mais que le mock ne recopie pas dans l'en-tête texte.
        Cookie xsrf = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        assertThat(xsrf.getSecure()).isTrue();
        assertThat(xsrf.getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(xsrf.isHttpOnly()).isFalse();
    }

    @Test
    void login_quandSucces_alorsLaCommandeTransporteLesValeursBrutes() throws Exception {
        // When
        login(credentials("  Alice@Example.ORG ", "S3cret!pass-long")).andExpect(status().isOk());

        // Then : la normalisation est l'affaire du domaine, pas du contrôleur
        ArgumentCaptor<LoginCommand> captor = ArgumentCaptor.forClass(LoginCommand.class);
        verify(loginUseCase).login(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("  Alice@Example.ORG ");
        assertThat(captor.getValue().password()).isEqualTo("S3cret!pass-long");
    }

    @Test
    void login_quandMotDePasseEspacesSeulement_alorsAccepteSansRegleDeLongueur() throws Exception {
        // When / Then : aucun @NotBlank ni @Size sur le mot de passe (on ne révèle pas la politique)
        login(credentials("alice@example.org", "   ")).andExpect(status().isOk());
        login(credentials("alice@example.org", "x")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- login : échec d'identification

    @Test
    void login_quandInvalidCredentials_alorsProblemDetail401SansCookie() throws Exception {
        // Given
        when(loginUseCase.login(any())).thenThrow(new InvalidCredentialsException());

        // When
        MvcResult result = login(credentials("alice@example.org", "mauvais"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.title").value("Identifiants invalides"))
                .andExpect(jsonPath("$.detail").value("E-mail ou mot de passe incorrect"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andReturn();

        // Then
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .noneMatch(c -> c.startsWith("ACCESS_TOKEN="));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("alice").doesNotContain("mauvais");
    }

    // ---------------------------------------------------------------- login : validation (RM-AUTH-09)

    @Test
    void login_quandEmailAbsent_alors400RequiredSurEmail() throws Exception {
        // When / Then
        assertValidation(login(credentials(null, "S3cret!pass-long")), "email", "REQUIRED");
        verifyNoInteractions(loginUseCase);
    }

    @Test
    void login_quandEmailVide_alors400BlankSurEmail() throws Exception {
        // When / Then
        assertValidation(login(credentials("", "S3cret!pass-long")), "email", "BLANK");
        verifyNoInteractions(loginUseCase);
    }

    @Test
    void login_quandEmailMalForme_alors400InvalidFormatSurEmail() throws Exception {
        // When / Then
        assertValidation(login(credentials("pas-un-email", "S3cret!pass-long")), "email", "INVALID_FORMAT");
        verifyNoInteractions(loginUseCase);
    }

    @Test
    void login_quandMotDePasseAbsent_alors400RequiredSurPassword() throws Exception {
        // When / Then
        assertValidation(login(credentials("alice@example.org", null)), "password", "REQUIRED");
        verifyNoInteractions(loginUseCase);
    }

    @Test
    void login_quandMotDePasseVide_alors400BlankSurPassword() throws Exception {
        // When / Then
        assertValidation(login(credentials("alice@example.org", "")), "password", "BLANK");
        verifyNoInteractions(loginUseCase);
    }

    @Test
    void login_quandEmailDe300Caracteres_alors400TooLongUneSeuleErreur() throws Exception {
        // When / Then
        String email = "a".repeat(288) + "@example.org";
        assertValidation(login(credentials(email, "S3cret!pass-long")), "email", "TOO_LONG");
        verifyNoInteractions(loginUseCase);
    }

    @Test
    void login_quandEmailEtMotDePasseInvalides_alorsUneErreurParChamp() throws Exception {
        // When / Then
        login(credentials("pas-un-email", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(2)));
    }

    @Test
    void login_quandJsonIllisible_alors400ValidationFailed() throws Exception {
        // When / Then
        login("{ pas du json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(loginUseCase);
    }

    @Test
    void login_quandCorpsAbsent_alors400ValidationFailed() throws Exception {
        // When / Then
        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void login_quandCorpsDeMauvaisType_alors400ValidationFailed() throws Exception {
        // When / Then
        login("[1,2]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------------------------------------------------------------- login : route publique, sans CSRF (RM-AUTH-10, 25)

    @Test
    void login_quandSansAuthentificationNiCsrf_alorsAtteintLeControleur() throws Exception {
        // When / Then
        login(credentials("alice@example.org", "S3cret!pass-long")).andExpect(status().isOk());
        verify(loginUseCase).login(any());
    }

    @Test
    void login_quandCookieAccessTokenExpirePresent_alorsAtteintQuandMemeLeControleur() throws Exception {
        // Given : un navigateur dont le cookie a expiré tente de se reconnecter
        Cookie expired = new Cookie("ACCESS_TOKEN", TestJwts.expired());

        // When / Then : ni 401 du resource server, ni 403 CSRF
        mockMvc.perform(post(LOGIN).cookie(expired).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("alice@example.org", "S3cret!pass-long")))
                .andExpect(status().isOk());
        verify(loginUseCase).login(any());
    }

    @Test
    void login_quandCookieNonJwtPresent_alorsAtteintLeControleur() throws Exception {
        // When / Then
        mockMvc.perform(post(LOGIN).cookie(new Cookie("ACCESS_TOKEN", "n-importe-quoi"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("alice@example.org", "S3cret!pass-long")))
                .andExpect(status().isOk());
    }

    @Test
    void login_quandEnTeteAuthorizationInvalide_alorsAtteintLeControleur() throws Exception {
        // When / Then
        mockMvc.perform(post(LOGIN).header("Authorization", "Bearer garbage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("alice@example.org", "S3cret!pass-long")))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- /me (RM-AUTH-24, 26, 28)

    private void givenCurrentUser(String email) {
        when(getCurrentUserUseCase.current(any())).thenReturn(
                new CurrentUser(userId.value().toString(), email, Set.of(Role.MEMBER), EXPIRES_AT));
    }

    @Test
    void me_quandBearerValide_alors200AvecIdentite() throws Exception {
        // Given
        givenCurrentUser("alice@example.org");
        String token = TestJwts.withRoles(userId.value().toString(), "alice@example.org", "MEMBER");

        // When / Then
        mockMvc.perform(get(ME).header("Authorization", TestJwts.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.value().toString()))
                .andExpect(jsonPath("$.email").value("alice@example.org"))
                .andExpect(jsonPath("$.roles[0]").value("MEMBER"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-20T11:00:00Z"));
    }

    @Test
    void me_quandCookieValide_alors200() throws Exception {
        // Given
        givenCurrentUser("alice@example.org");
        String token = TestJwts.withRoles(userId.value().toString(), "alice@example.org", "MEMBER");

        // When / Then
        mockMvc.perform(get(ME).cookie(new Cookie("ACCESS_TOKEN", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.org"));
    }

    @Test
    void me_quandJwtValide_alorsLeControleurTransmetLesClaimsAuUseCase() throws Exception {
        // Given
        givenCurrentUser("alice@example.org");
        String token = TestJwts.withRoles("sub-123", "alice@example.org", "MEMBER", "LIBRARIAN");

        // When
        mockMvc.perform(get(ME).header("Authorization", TestJwts.bearer(token))).andExpect(status().isOk());

        // Then
        ArgumentCaptor<GetCurrentUserQuery> captor = ArgumentCaptor.forClass(GetCurrentUserQuery.class);
        verify(getCurrentUserUseCase).current(captor.capture());
        assertThat(captor.getValue().subject()).isEqualTo("sub-123");
        assertThat(captor.getValue().email()).isEqualTo("alice@example.org");
        assertThat(captor.getValue().roles()).containsExactly("MEMBER", "LIBRARIAN");
        assertThat(captor.getValue().expiresAt()).isNotNull();
    }

    @Test
    void me_quandJetonSansClaimEmail_alors200EmailNull() throws Exception {
        // Given : jeton de type /dev/token (profil local), sans claim email
        givenCurrentUser(null);
        String token = TestJwts.withoutEmail("sujet-brut", "MEMBER");

        // When
        mockMvc.perform(get(ME).header("Authorization", TestJwts.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(nullValue()));

        // Then
        ArgumentCaptor<GetCurrentUserQuery> captor = ArgumentCaptor.forClass(GetCurrentUserQuery.class);
        verify(getCurrentUserUseCase).current(captor.capture());
        assertThat(captor.getValue().email()).isNull();
        assertThat(captor.getValue().subject()).isEqualTo("sujet-brut");
    }

    @Test
    void me_quandSansCookieNiEnTete_alors401ProblemDetailUnauthenticated() throws Exception {
        // When
        MvcResult result = mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401))
                .andReturn();

        // Then
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        verifyNoInteractions(getCurrentUserUseCase);
    }

    @Test
    void me_quandJetonExpireEnEnTete_alors401ProblemDetail() throws Exception {
        // When / Then
        mockMvc.perform(get(ME).header("Authorization", TestJwts.bearer(TestJwts.expired())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void me_quandJetonExpireEnCookie_alors401ProblemDetail() throws Exception {
        // When / Then
        mockMvc.perform(get(ME).cookie(new Cookie("ACCESS_TOKEN", TestJwts.expired())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void me_quandJetonSigneAvecUneAutreCle_alors401() throws Exception {
        // When / Then
        mockMvc.perform(get(ME).header("Authorization", TestJwts.bearer(TestJwts.signedWithOtherKey())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void me_quandCookieVide_alors401() throws Exception {
        // When / Then
        mockMvc.perform(get(ME).cookie(new Cookie("ACCESS_TOKEN", "")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_quandEnTeteEtCookie_alorsEnTetePrime() throws Exception {
        // Given : cookie expiré, en-tête valide
        givenCurrentUser("alice@example.org");
        String valid = TestJwts.member();

        // When / Then
        mockMvc.perform(get(ME).cookie(new Cookie("ACCESS_TOKEN", TestJwts.expired()))
                        .header("Authorization", TestJwts.bearer(valid)))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- logout (RM-AUTH-27)

    @Test
    void logout_quandSansCookie_alors204EtCookieVideMaxAgeZero() throws Exception {
        // When
        MvcResult result = mockMvc.perform(post(LOGOUT)).andExpect(status().isNoContent()).andReturn();

        // Then
        String cookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(c -> c.startsWith("ACCESS_TOKEN=")).findFirst().orElseThrow();
        assertThat(cookie).startsWith("ACCESS_TOKEN=;")
                .contains("Max-Age=0").contains("Path=/api").contains("Secure")
                .contains("HttpOnly").contains("SameSite=Strict");
    }

    @Test
    void logout_quandCookieSansEnTeteXsrf_alors403ProblemDetailAccessDenied() throws Exception {
        // When
        MvcResult result = mockMvc.perform(post(LOGOUT).cookie(new Cookie("ACCESS_TOKEN", TestJwts.member())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andReturn();

        // Then
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("ACCESS_TOKEN="));
    }

    @Test
    void logout_quandCookieAvecXsrfValide_alors204EtCookieEfface() throws Exception {
        // When
        MvcResult result = mockMvc.perform(post(LOGOUT)
                        .cookie(new Cookie("ACCESS_TOKEN", TestJwts.member()), new Cookie("XSRF-TOKEN", "csrf-value"))
                        .header("X-XSRF-TOKEN", "csrf-value"))
                .andExpect(status().isNoContent()).andReturn();

        // Then
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .anyMatch(c -> c.startsWith("ACCESS_TOKEN=;") && c.contains("Max-Age=0"));
    }

    @Test
    void logout_quandCookieAvecXsrfIncorrect_alors403() throws Exception {
        // When / Then
        mockMvc.perform(post(LOGOUT)
                        .cookie(new Cookie("ACCESS_TOKEN", TestJwts.member()), new Cookie("XSRF-TOKEN", "csrf-value"))
                        .header("X-XSRF-TOKEN", "autre-valeur"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void logout_quandCookieExpireEtXsrfValide_alors204() throws Exception {
        // When / Then : la route reste publique même avec un cookie expiré
        mockMvc.perform(post(LOGOUT)
                        .cookie(new Cookie("ACCESS_TOKEN", TestJwts.expired()), new Cookie("XSRF-TOKEN", "v"))
                        .header("X-XSRF-TOKEN", "v"))
                .andExpect(status().isNoContent());
    }

    @Test
    void logout_quandBearerSansXsrf_alors204() throws Exception {
        // When / Then : un client non navigateur est dispensé de CSRF
        mockMvc.perform(post(LOGOUT).header("Authorization", TestJwts.bearer(TestJwts.member())))
                .andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- routes protégées (RM-AUTH-10, 28)

    @Test
    void books_quandAnonyme_alors401ProblemDetail() throws Exception {
        // When / Then
        mockMvc.perform(get("/api/v1/books"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void creationLivre_quandMembreEnBearer_alors403ProblemDetailAccessDenied() throws Exception {
        // When / Then
        mockMvc.perform(post("/api/v1/books").header("Authorization", TestJwts.bearer(TestJwts.member()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void logout_quandAppeleSansUseCase_alorsAucunUseCaseSollicite() throws Exception {
        // When
        mockMvc.perform(post(LOGOUT)).andExpect(status().isNoContent());

        // Then : la déconnexion est du pur transport HTTP
        verify(loginUseCase, never()).login(any());
        verifyNoInteractions(getCurrentUserUseCase);
    }
}
