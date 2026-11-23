package com.example.library.config.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Force la lecture du jeton CSRF (chargé paresseusement) pour que le cookie XSRF-TOKEN soit posé dès la première
 * réponse, y compris à la connexion, afin que le navigateur puisse le renvoyer dans X-XSRF-TOKEN.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    /**
     * Posé une fois le contrôle CSRF passé (ce filtre suit CsrfFilter) : le jeton du cookie d'authentification
     * n'est lisible qu'à partir de là, voir {@link CsrfAwareBearerTokenResolver}.
     */
    static final String CSRF_PASSED_ATTRIBUTE = CsrfCookieFilter.class.getName() + ".CSRF_PASSED";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(CSRF_PASSED_ATTRIBUTE, Boolean.TRUE);
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
