package com.example.library.identity.adapter.in.web;

import com.example.library.config.security.JwtProperties;
import com.example.library.identity.domain.model.AccessToken;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** RM-AUTH-23 et RM-AUTH-27 : cookie HttpOnly, Secure, SameSite=Strict, Path=/api portant le jeton. */
@Component
class AccessTokenCookies {

    static final String PATH = "/api";

    private final JwtProperties properties;

    AccessTokenCookies(JwtProperties properties) {
        this.properties = properties;
    }

    ResponseCookie issue(AccessToken token) {
        return base(token.value()).maxAge(properties.ttl()).build();
    }

    /** Cookie vidé (Max-Age=0), mêmes attributs que celui posé à la connexion. */
    ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH);
    }
}
