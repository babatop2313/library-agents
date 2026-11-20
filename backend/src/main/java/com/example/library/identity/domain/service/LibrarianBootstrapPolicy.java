package com.example.library.identity.domain.service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.example.library.identity.domain.exception.InvalidEmailException;
import com.example.library.identity.domain.exception.PasswordPolicyViolationException;
import com.example.library.identity.domain.model.BootstrapOutcome;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.PasswordHash;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.User;

/** RM-AUTH-08 : décide si le premier bibliothécaire doit être créé au démarrage. */
public final class LibrarianBootstrapPolicy {

    private final PasswordPolicy passwordPolicy;

    public LibrarianBootstrapPolicy() {
        this(new PasswordPolicy());
    }

    public LibrarianBootstrapPolicy(PasswordPolicy passwordPolicy) {
        this.passwordPolicy = passwordPolicy;
    }

    /**
     * Décision dans l'ordre : bibliothécaire existant, configuration absente, e-mail invalide, mot de passe hors
     * politique, e-mail pris par un non-bibliothécaire. {@link BootstrapOutcome#CREATED} signifie « la création
     * est autorisée ». {@code findByEmail} n'est appelé qu'une fois les contrôles précédents passés.
     */
    public BootstrapOutcome decide(boolean librarianExists, String rawEmail, String rawPassword,
                                   Function<Email, Optional<User>> findByEmail) {
        if (librarianExists) {
            return BootstrapOutcome.LIBRARIAN_ALREADY_PRESENT;
        }
        if (isBlank(rawEmail) || isBlank(rawPassword)) {
            return BootstrapOutcome.MISSING_CONFIGURATION;
        }
        Email email;
        try {
            email = Email.of(rawEmail);
        } catch (InvalidEmailException e) {
            return BootstrapOutcome.INVALID_EMAIL;
        }
        try {
            passwordPolicy.checkNewPassword(rawPassword);
        } catch (PasswordPolicyViolationException e) {
            return BootstrapOutcome.WEAK_PASSWORD;
        }
        if (findByEmail.apply(email).isPresent()) {
            return BootstrapOutcome.EMAIL_TAKEN_BY_NON_LIBRARIAN;
        }
        return BootstrapOutcome.CREATED;
    }

    /** Crée le bibliothécaire d'amorçage : rôle LIBRARIAN seul, actif. */
    public User createLibrarian(String rawEmail, PasswordHash passwordHash, Instant now) {
        return User.register(Email.of(rawEmail), passwordHash, Set.of(Role.LIBRARIAN), now);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
