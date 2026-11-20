package com.example.library.identity.adapter.out.crypto;

import java.nio.charset.StandardCharsets;

import com.example.library.identity.application.port.out.PasswordHasher;
import com.example.library.identity.domain.model.PasswordHash;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** Hachage BCrypt (RM-AUTH-03). Le hash factice est calculé une fois au démarrage, au même coût qu'un vrai. */
@Component
class BCryptPasswordHasher implements PasswordHasher {

    private static final int MAX_BYTES = 72;
    private static final String DUMMY_CANDIDATE = "dummy-password-never-matching";

    private final BCryptPasswordEncoder encoder;
    private final PasswordHash dummyHash;

    BCryptPasswordHasher(@Value("${app.security.bcrypt.strength:10}") int strength) {
        this.encoder = new BCryptPasswordEncoder(strength);
        this.dummyHash = new PasswordHash(encoder.encode("dummy-password-for-timing-only"));
    }

    @Override
    public PasswordHash hash(String rawPassword) {
        if (rawPassword == null || byteLength(rawPassword) > MAX_BYTES) {
            // la politique de mot de passe doit avoir refusé cette valeur : jamais de troncature silencieuse
            throw new IllegalArgumentException("Mot de passe non hachable");
        }
        return new PasswordHash(encoder.encode(rawPassword));
    }

    @Override
    public boolean matches(String rawPassword, PasswordHash hash) {
        if (rawPassword == null || byteLength(rawPassword) > MAX_BYTES) {
            // entrée non comparable : on exécute tout de même une comparaison, pour garder le même temps de réponse
            encoder.matches(DUMMY_CANDIDATE, dummyHash.value());
            return false;
        }
        return encoder.matches(rawPassword, hash.value());
    }

    @Override
    public PasswordHash dummyHash() {
        return dummyHash;
    }

    private static int byteLength(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
