package com.example.library.identity.application.port.out;

import com.example.library.identity.domain.model.PasswordHash;

public interface PasswordHasher {

    PasswordHash hash(String rawPassword);

    /** Ne lève jamais : renvoie false (après une vraie comparaison) si l'entrée n'est pas comparable. */
    boolean matches(String rawPassword, PasswordHash hash);

    /** Hash factice, de même coût qu'un vrai, comparé quand l'utilisateur est inconnu (temps de réponse uniforme). */
    PasswordHash dummyHash();
}
