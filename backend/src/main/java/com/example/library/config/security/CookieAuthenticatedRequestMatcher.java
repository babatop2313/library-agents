package com.example.library.config.security;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.WebUtils;

/**
 * RM-AUTH-25 : la protection CSRF n'est exigée que si la requête est modifiante, porte le cookie d'authentification
 * et ne porte aucun en-tête Authorization (les requêtes Bearer ne sont pas exposées au CSRF).
 */
public class CookieAuthenticatedRequestMatcher implements RequestMatcher {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "TRACE", "OPTIONS");

    private final String cookieName;

    public CookieAuthenticatedRequestMatcher(String cookieName) {
        this.cookieName = cookieName;
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        return !SAFE_METHODS.contains(request.getMethod())
                && request.getHeader(HttpHeaders.AUTHORIZATION) == null
                && WebUtils.getCookie(request, cookieName) != null;
    }
}
