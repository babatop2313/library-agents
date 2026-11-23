package com.example.library.catalog.domain.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.example.library.catalog.domain.exception.InvalidCatalogSearchException;

/**
 * Texte de recherche du catalogue (RM-CAT-17) : rogné, 100 caractères maximum ; vide = pas de filtre.
 * La correspondance est une sous-chaîne littérale du titre OU de l'auteur, insensible à la casse
 * et sensible aux accents ; l'ISBN n'est pas cherché.
 */
public record SearchText(String value) {

    public static final int MAX_LENGTH = 100;
    public static final SearchText NONE = new SearchText("");

    public SearchText {
        Objects.requireNonNull(value, "value");
    }

    /** Rogne le texte ; null, vide ou blanc donne {@link #NONE}. */
    public static SearchText of(String raw) {
        List<FieldViolation> violations = violationsOf(raw);
        if (!violations.isEmpty()) {
            throw new InvalidCatalogSearchException(violations);
        }
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        return new SearchText(raw.strip());
    }

    public static List<FieldViolation> violationsOf(String raw) {
        if (raw != null && raw.strip().length() > MAX_LENGTH) {
            return List.of(new FieldViolation("q", ViolationCode.TOO_LONG));
        }
        return List.of();
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    /** Sémantique de référence (fake des tests, oracle) ; la base la reproduit par LIKE. */
    public boolean matches(Book book) {
        if (isEmpty()) {
            return true;
        }
        String needle = value.toLowerCase(Locale.ROOT);
        return book.getTitle().toLowerCase(Locale.ROOT).contains(needle)
                || book.getAuthor().toLowerCase(Locale.ROOT).contains(needle);
    }
}
