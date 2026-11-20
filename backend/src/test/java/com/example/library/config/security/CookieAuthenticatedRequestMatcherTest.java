package com.example.library.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** RM-AUTH-25 : CSRF exigé si méthode non sûre ET cookie présent ET pas d'en-tête Authorization. */
class CookieAuthenticatedRequestMatcherTest {

    private final CookieAuthenticatedRequestMatcher matcher = new CookieAuthenticatedRequestMatcher("ACCESS_TOKEN");

    private static MockHttpServletRequest request(String method, boolean cookie, boolean authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/books");
        if (cookie) {
            request.setCookies(new Cookie("ACCESS_TOKEN", "jwt"));
        }
        if (authorization) {
            request.addHeader("Authorization", "Bearer jwt");
        }
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void matches_quandMethodeNonSureAvecCookieSansAuthorization_alorsCsrfExige(String method) {
        // When / Then
        assertThat(matcher.matches(request(method, true, false))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void matches_quandMethodeNonSureAvecCookieEtAuthorization_alorsDispense(String method) {
        // When / Then
        assertThat(matcher.matches(request(method, true, true))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void matches_quandMethodeNonSureSansCookie_alorsDispense(String method) {
        // When / Then
        assertThat(matcher.matches(request(method, false, false))).isFalse();
        assertThat(matcher.matches(request(method, false, true))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "TRACE", "OPTIONS"})
    void matches_quandMethodeSure_alorsJamaisDeCsrf(String method) {
        // When / Then
        assertThat(matcher.matches(request(method, true, false))).isFalse();
        assertThat(matcher.matches(request(method, false, false))).isFalse();
    }

    @Test
    void matches_quandAutreCookieQueLeCookieDeJeton_alorsDispense() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/books");
        request.setCookies(new Cookie("XSRF-TOKEN", "x"));

        // When / Then
        assertThat(matcher.matches(request)).isFalse();
    }
}
