package com.example.library.identity.domain.service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.example.library.identity.domain.exception.InvalidEmailException;
import com.example.library.identity.domain.exception.PasswordPolicyViolationException;
import com.example.library.identity.domain.model.DevMemberBootstrapOutcome;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.PasswordHash;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.User;

/**
 * RM-LOAN-30 : décide si le membre de test (poste de développement) doit être créé au démarrage.
 * Le mot de passe suit la même politique que celui des autres comptes (12 caractères minimum, 72 octets maximum).
 */
public final class DevMemberBootstrapPolicy {

    private final PasswordPolicy passwordPolicy;

    public DevMemberBootstrapPolicy() {
        this(new PasswordPolicy());
    }

    public DevMemberBootstrapPolicy(PasswordPolicy passwordPolicy) {
        this.passwordPolicy = passwordPolicy;
    }

    /**
     * Décision dans l'ordre : configuration absente ou vide, e-mail invalide, mot de passe hors politique, compte
     * existant (avec ou sans le rôle MEMBER). {@link DevMemberBootstrapOutcome#CREATED} signifie « la création est
     * autorisée ». {@code findByEmail} n'est appelé qu'une fois les contrôles précédents passés.
     */
    public DevMemberBootstrapOutcome decide(String rawEmail, String rawPassword,
                                            Function<Email, Optional<User>> findByEmail) {
        if (isBlank(rawEmail) || isBlank(rawPassword)) {
            return DevMemberBootstrapOutcome.MISSING_CONFIGURATION;
        }
        Email email;
        try {
            email = Email.of(rawEmail);
        } catch (InvalidEmailException e) {
            return DevMemberBootstrapOutcome.INVALID_EMAIL;
        }
        try {
            passwordPolicy.checkNewPassword(rawPassword);
        } catch (PasswordPolicyViolationException e) {
            return DevMemberBootstrapOutcome.WEAK_PASSWORD;
        }
        Optional<User> existing = findByEmail.apply(email);
        if (existing.isPresent()) {
            return existing.get().hasRole(Role.MEMBER)
                    ? DevMemberBootstrapOutcome.ALREADY_PRESENT
                    : DevMemberBootstrapOutcome.EMAIL_TAKEN_WITHOUT_MEMBER_ROLE;
        }
        return DevMemberBootstrapOutcome.CREATED;
    }

    /** Crée le membre de test : rôle MEMBER seul, actif. */
    public User createMember(String rawEmail, PasswordHash passwordHash, Instant now) {
        return User.register(Email.of(rawEmail), passwordHash, Set.of(Role.MEMBER), now);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
