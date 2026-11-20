package com.example.library.identity.domain.model;

import java.util.Locale;
import java.util.regex.Pattern;

import com.example.library.identity.domain.exception.InvalidEmailException;

/**
 * RM-AUTH-02 : e-mail normalisé (trim + minuscules), format d'adresse simple, 254 caractères au maximum.
 * La valeur portée est toujours la forme normalisée.
 */
public record Email(String value) {

    public static final int MAX_LENGTH = 254;

    private static final Pattern FORMAT = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public Email {
        if (value == null) {
            throw new InvalidEmailException();
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (isTooLong(value) || !FORMAT.matcher(value).matches()) {
            throw new InvalidEmailException();
        }
    }

    public static Email of(String raw) {
        return new Email(raw);
    }

    /** Format valide (après trim), sans tenir compte de la longueur. Null : faux. */
    public static boolean hasValidFormat(String raw) {
        return raw != null && FORMAT.matcher(raw.strip()).matches();
    }

    /** Longueur supérieure à 254 caractères après trim. Null : faux. */
    public static boolean isTooLong(String raw) {
        return raw != null && raw.strip().length() > MAX_LENGTH;
    }

    /** Forme masquée pour les journaux : « a***@example.org ». */
    public String masked() {
        int at = value.indexOf('@');
        return value.charAt(0) + "***" + value.substring(at);
    }
}
