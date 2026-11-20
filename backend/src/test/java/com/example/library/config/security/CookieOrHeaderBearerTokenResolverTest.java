package com.example.library.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** RM-AUTH-24 : l'en-tête prime sur le cookie ; login et logout ignorent tout jeton. */
class CookieOrHeaderBearerTokenResolverTest {

    private final CookieOrHeaderBearerTokenResolver resolver = new CookieOrHeaderBearerTokenResolver("ACCESS_TOKEN");

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    @Test
    void resolve_quandEnTeteBearerSeul_alorsJetonDeLEnTete() {
        // Given
        MockHttpServletRequest request = request("GET", "/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer header-token");

        // When / Then
        assertThat(resolver.resolve(request)).isEqualTo("header-token");
    }

    @Test
    void resolve_quandCookieSeul_alorsJetonDuCookie() {
        // Given
        MockHttpServletRequest request = request("GET", "/api/v1/auth/me");
        request.setCookies(new Cookie("ACCESS_TOKEN", "cookie-token"));

        // When / Then
        assertThat(resolver.resolve(request)).isEqualTo("cookie-token");
    }

    @Test
    void resolve_quandEnTeteEtCookie_alorsEnTetePrime() {
        // Given
        MockHttpServletRequest request = request("GET", "/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer header-token");
        request.setCookies(new Cookie("ACCESS_TOKEN", "cookie-token"));

        // When / Then
        assertThat(resolver.resolve(request)).isEqualTo("header-token");
    }

    @Test
    void resolve_quandAucunJeton_alorsNull() {
        // When / Then
        assertThat(resolver.resolve(request("GET", "/api/v1/books"))).isNull();
    }

    @Test
    void resolve_quandCookieVide_alorsNull() {
        // Given
        MockHttpServletRequest request = request("GET", "/api/v1/auth/me");
        request.setCookies(new Cookie("ACCESS_TOKEN", ""));

        // When / Then
        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void resolve_quandAutreCookie_alorsIgnore() {
        // Given
        MockHttpServletRequest request = request("GET", "/api/v1/auth/me");
        request.setCookies(new Cookie("OTHER", "x"));

        // When / Then
        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void resolve_quandLoginAvecCookieExpire_alorsNullPourNePasRejeterLaRoutePublique() {
        // Given
        MockHttpServletRequest request = request("POST", "/api/v1/auth/login");
        request.setCookies(new Cookie("ACCESS_TOKEN", "expired-token"));
        request.addHeader("Authorization", "Bearer header-token");

        // When / Then
        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void resolve_quandLogoutAvecCookie_alorsNull() {
        // Given
        MockHttpServletRequest request = request("POST", "/api/v1/auth/logout");
        request.setCookies(new Cookie("ACCESS_TOKEN", "some-token"));

        // When / Then
        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void resolve_quandNomDeCookieConfigure_alorsLeNomConfigureEstLu() {
        // Given
        CookieOrHeaderBearerTokenResolver custom = new CookieOrHeaderBearerTokenResolver("SESSION_JWT");
        MockHttpServletRequest request = request("GET", "/api/v1/auth/me");
        request.setCookies(new Cookie("SESSION_JWT", "custom-token"), new Cookie("ACCESS_TOKEN", "ignored"));

        // When / Then
        assertThat(custom.resolve(request)).isEqualTo("custom-token");
    }
}
