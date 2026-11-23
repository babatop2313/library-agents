package com.example.library.identity.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Jeton d'accès émis (JWT) et sa date d'expiration. Jamais affiché : toString masqué. */
public record AccessToken(String value, Instant expiresAt) {

    public AccessToken {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "AccessToken[expiresAt=" + expiresAt + ", value=***]";
    }
}
