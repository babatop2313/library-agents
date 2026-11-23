package com.example.library.identity.domain.service;

import java.util.Optional;

import com.example.library.identity.domain.exception.InvalidCredentialsException;
import com.example.library.identity.domain.model.User;

/**
 * RM-AUTH-04 et RM-AUTH-07 : décide si une connexion est acceptée. Fonction pure : la comparaison BCrypt
 * est déjà exécutée par l'appelant (toujours, même si l'utilisateur est inconnu) et son résultat est fourni.
 * Tous les échecs lèvent la même exception.
 */
public final class LoginPolicy {

    private final PasswordPolicy passwordPolicy;

    public LoginPolicy() {
        this(new PasswordPolicy());
    }

    public LoginPolicy(PasswordPolicy passwordPolicy) {
        this.passwordPolicy = passwordPolicy;
    }

    public User authenticate(Optional<User> user, String rawPassword, boolean hashMatches) {
        if (user.isEmpty()
                || !passwordPolicy.isVerifiable(rawPassword)
                || !hashMatches
                || !user.get().canLogIn()) {
            throw new InvalidCredentialsException();
        }
        return user.get();
    }
}
