package com.example.library.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** RM-AUTH-25 : l'exemption CSRF du resource server ne doit dépendre que de l'en-tête Authorization. */
class CsrfAwareBearerTokenResolverTest {

    private final CsrfAwareBearerTokenResolver resolver =
            new CsrfAwareBearerTokenResolver(new CookieOrHeaderBearerTokenResolver("ACCESS_TOKEN"));

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/books");
    }

    @Test
    void resolve_quandEnTeteAuthorization_alorsDelegueSansAttribut() {
        // Given
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer header-token");

        // When / Then
        assertThat(resolver.resolve(request)).isEqualTo("header-token");
    }

    @Test
    void resolve_quandCookieSeulSansAttributCsrf_alorsNull() {
        // Given : pendant CsrfFilter, le cookie ne doit pas rendre la requête exemptée
        MockHttpServletRequest request = request();
        request.setCookies(new Cookie("ACCESS_TOKEN", "cookie-token"));

        // When / Then
        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void resolve_quandCookieSeulAvecAttributCsrf_alorsJetonDuCookie() {
        // Given
        MockHttpServletRequest request = request();
        request.setCookies(new Cookie("ACCESS_TOKEN", "cookie-token"));
        request.setAttribute(CsrfCookieFilter.CSRF_PASSED_ATTRIBUTE, Boolean.TRUE);

        // When / Then
        assertThat(resolver.resolve(request)).isEqualTo("cookie-token");
    }

    @Test
    void resolve_quandAttributSansCookieNiEnTete_alorsNull() {
        // Given
        MockHttpServletRequest request = request();
        request.setAttribute(CsrfCookieFilter.CSRF_PASSED_ATTRIBUTE, Boolean.TRUE);

        // When / Then
        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void resolve_quandEnTeteEtCookieAvecAttribut_alorsEnTetePrime() {
        // Given
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer header-token");
        request.setCookies(new Cookie("ACCESS_TOKEN", "cookie-token"));
        request.setAttribute(CsrfCookieFilter.CSRF_PASSED_ATTRIBUTE, Boolean.TRUE);

        // When / Then
        assertThat(resolver.resolve(request)).isEqualTo("header-token");
    }

    @Test
    void resolve_quandLoginAvecAttribut_alorsNullCarLeDelegueIgnoreLogin() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setCookies(new Cookie("ACCESS_TOKEN", "expired"));
        request.setAttribute(CsrfCookieFilter.CSRF_PASSED_ATTRIBUTE, Boolean.TRUE);

        // When / Then
        assertThat(resolver.resolve(request)).isNull();
    }
}
