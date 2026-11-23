package com.example.library.identity.application.fake;

import java.nio.charset.StandardCharsets;

import com.example.library.identity.application.port.out.PasswordHasher;
import com.example.library.identity.domain.model.PasswordHash;

/** Faux hacheur déterministe ({@code fake:<mot de passe>}) qui compte les comparaisons et les hachages. */
public class FakePasswordHasher implements PasswordHasher {

    public static final PasswordHash DUMMY = new PasswordHash("fake:dummy-never-matching");

    private int matchesCalls;
    private int hashCalls;
    private PasswordHash lastReference;

    public static PasswordHash hashOf(String raw) {
        return new PasswordHash("fake:" + raw);
    }

    public int matchesCalls() {
        return matchesCalls;
    }

    public int hashCalls() {
        return hashCalls;
    }

    public PasswordHash lastReference() {
        return lastReference;
    }

    @Override
    public PasswordHash hash(String rawPassword) {
        hashCalls++;
        return hashOf(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, PasswordHash hash) {
        matchesCalls++;
        lastReference = hash;
        if (rawPassword == null || rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        return hash.equals(hashOf(rawPassword));
    }

    @Override
    public PasswordHash dummyHash() {
        return DUMMY;
    }
}
