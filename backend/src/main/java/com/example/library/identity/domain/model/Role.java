package com.example.library.identity.domain.model;

import java.util.Optional;

public enum Role {
    LIBRARIAN,
    MEMBER;

    /** Rôle correspondant à une valeur de claim ; vide si la valeur est inconnue. */
    public static Optional<Role> fromClaim(String claim) {
        if (claim == null) {
            return Optional.empty();
        }
        for (Role role : values()) {
            if (role.name().equals(claim)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
