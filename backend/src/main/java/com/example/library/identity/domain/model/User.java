package com.example.library.identity.domain.model;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.example.library.identity.domain.exception.InvalidUserException;

/** Utilisateur de l'application (RM-AUTH-01). Aucun setter : l'état ne change que par des méthodes métier. */
public final class User {

    private final UserId id;
    private final Email email;
    private final PasswordHash passwordHash;
    private final boolean enabled;
    private final Set<Role> roles;
    private final Instant createdAt;

    private User(UserId id, Email email, PasswordHash passwordHash, boolean enabled,
                 Set<Role> roles, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = Objects.requireNonNull(email, "email");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.enabled = enabled;
        Objects.requireNonNull(roles, "roles");
        if (roles.isEmpty()) {
            throw new InvalidUserException();
        }
        this.roles = Collections.unmodifiableSet(EnumSet.copyOf(roles));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Création d'un nouvel utilisateur actif ; au moins un rôle. */
    public static User register(Email email, PasswordHash passwordHash, Set<Role> roles, Instant now) {
        return new User(UserId.newId(), email, passwordHash, true, roles, now);
    }

    /** Reconstitution depuis la persistance : aucune règle métier rejouée (hors invariants de structure). */
    public static User restore(UserId id, Email email, PasswordHash passwordHash, boolean enabled,
                               Set<Role> roles, Instant createdAt) {
        return new User(id, email, passwordHash, enabled, roles, createdAt);
    }

    /** RM-AUTH-04 : un compte désactivé ne peut pas se connecter. */
    public boolean canLogIn() {
        return enabled;
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public UserId id() { return id; }
    public Email email() { return email; }
    public PasswordHash passwordHash() { return passwordHash; }
    public boolean enabled() { return enabled; }
    public Set<Role> roles() { return roles; }
    public Instant createdAt() { return createdAt; }
}
