package com.example.library.identity.domain.service;

import java.nio.charset.StandardCharsets;

import com.example.library.identity.domain.exception.PasswordPolicyViolationException;
import com.example.library.identity.domain.exception.PasswordPolicyViolationException.Reason;

/**
 * RM-AUTH-03 : politique des mots de passe. Création : 12 caractères minimum, 72 octets UTF-8 maximum
 * (limite BCrypt, jamais de troncature). Connexion : aucun minimum, on ne révèle pas la politique.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    /** Vérifie un nouveau mot de passe ; lève {@link PasswordPolicyViolationException} sans reprendre la valeur. */
    public void checkNewPassword(String raw) {
        if (raw == null || raw.codePointCount(0, raw.length()) < MIN_LENGTH) {
            throw new PasswordPolicyViolationException(Reason.TOO_SHORT);
        }
        if (byteLength(raw) > MAX_BYTES) {
            throw new PasswordPolicyViolationException(Reason.TOO_LONG);
        }
    }

    /** Un mot de passe de connexion est comparable s'il est non vide et tient dans 72 octets. */
    public boolean isVerifiable(String raw) {
        return raw != null && !raw.isEmpty() && byteLength(raw) <= MAX_BYTES;
    }

    private static int byteLength(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8).length;
    }
}
