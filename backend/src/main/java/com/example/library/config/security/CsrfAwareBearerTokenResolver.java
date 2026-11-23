package com.example.library.config.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

/**
 * Enveloppe le resolver cookie/en-tête pour que l'exemption CSRF automatique du resource server
 * (BearerTokenRequestMatcher, qui interroge ce resolver) ne dépende que de l'en-tête Authorization.
 * Pendant CsrfFilter, le cookie n'est pas encore lisible : la requête n'est donc pas exemptée. Une fois le contrôle
 * CSRF passé ({@link CsrfCookieFilter} pose l'attribut), le cookie est lu normalement pour l'authentification.
 */
public class CsrfAwareBearerTokenResolver implements BearerTokenResolver {

    private final BearerTokenResolver delegate;

    public CsrfAwareBearerTokenResolver(BearerTokenResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        boolean csrfPassed = request.getAttribute(CsrfCookieFilter.CSRF_PASSED_ATTRIBUTE) != null;
        if (csrfPassed || request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            return delegate.resolve(request);
        }
        return null;
    }
}
