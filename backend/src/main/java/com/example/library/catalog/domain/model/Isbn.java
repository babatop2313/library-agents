package com.example.library.catalog.domain.model;

import java.util.regex.Pattern;

import com.example.library.catalog.domain.exception.InvalidIsbnException;

/** ISBN normalisé : exactement 13 chiffres, sans séparateur (RM-07). */
public record Isbn(String value) {

    private static final Pattern THIRTEEN_DIGITS = Pattern.compile("^[0-9]{13}$");
    private static final Pattern SEPARATORS = Pattern.compile("[\s-]");

    public Isbn {
        if (value == null || !THIRTEEN_DIGITS.matcher(value).matches()) {
            throw new InvalidIsbnException();
        }
    }

    /** Retire espaces et tirets puis valide la forme. */
    public static Isbn of(String raw) {
        if (raw == null) {
            throw new InvalidIsbnException();
        }
        return new Isbn(SEPARATORS.matcher(raw).replaceAll(""));
    }

    public static boolean isValid(String raw) {
        if (raw == null) {
            return false;
        }
        return THIRTEEN_DIGITS.matcher(SEPARATORS.matcher(raw).replaceAll("")).matches();
    }
}
