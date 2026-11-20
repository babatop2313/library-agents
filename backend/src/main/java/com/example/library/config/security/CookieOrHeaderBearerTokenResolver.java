package com.example.library.config.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.util.WebUtils;

/**
 * RM-AUTH-24 : lit le jeton dans l'en-tête Authorization s'il est présent (clients non navigateurs), sinon dans le
 * cookie. Aucun jeton n'est lu pour la connexion et la déconnexion : un cookie expiré ou corrompu ne doit pas les
 * faire échouer en 401 avant d'atteindre le contrôleur.
 */
public class CookieOrHeaderBearerTokenResolver implements BearerTokenResolver {

    private static final RequestMatcher LOGIN_OR_LOGOUT = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/login"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/logout"));

    private final BearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
    private final String cookieName;

    public CookieOrHeaderBearerTokenResolver(String cookieName) {
        this.cookieName = cookieName;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        if (LOGIN_OR_LOGOUT.matches(request)) {
            return null;
        }
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            // l'en-tête prime : on ne retombe pas sur le cookie (cohérent avec la règle CSRF)
            return headerResolver.resolve(request);
        }
        Cookie cookie = WebUtils.getCookie(request, cookieName);
        return cookie != null && StringUtils.hasText(cookie.getValue()) ? cookie.getValue() : null;
    }
}
