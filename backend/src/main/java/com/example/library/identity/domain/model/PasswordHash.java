package com.example.library.identity.domain.model;

import java.util.Objects;

/** Mot de passe haché (BCrypt, sel inclus). Jamais affiché : toString masqué. */
public record PasswordHash(String value) {

    public PasswordHash {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Le hash du mot de passe est vide");
        }
    }

    @Override
    public String toString() {
        return "PasswordHash[***]";
    }
}
