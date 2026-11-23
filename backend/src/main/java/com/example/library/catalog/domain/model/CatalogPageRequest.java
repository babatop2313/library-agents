package com.example.library.catalog.domain.model;

import java.util.ArrayList;
import java.util.List;

import com.example.library.catalog.domain.exception.InvalidCatalogSearchException;

/** Page demandée du catalogue (RM-CAT-15) : numéro de page (base 0) et taille (1 à 50, 10 par défaut). */
public record CatalogPageRequest(int page, int size) {

    public static final int MIN_PAGE = 0;
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 50;
    public static final int DEFAULT_SIZE = 10;

    public CatalogPageRequest {
        List<FieldViolation> violations = violationsOf(page, size);
        if (!violations.isEmpty()) {
            throw new InvalidCatalogSearchException(violations);
        }
    }

    /** Valeurs par défaut si absentes, sinon validation des bornes. */
    public static CatalogPageRequest of(Integer page, Integer size) {
        return new CatalogPageRequest(page == null ? MIN_PAGE : page, size == null ? DEFAULT_SIZE : size);
    }

    /** Violations de bornes ; une valeur absente (null) vaut sa valeur par défaut. */
    public static List<FieldViolation> violationsOf(Integer page, Integer size) {
        List<FieldViolation> violations = new ArrayList<>();
        if (page != null && page < MIN_PAGE) {
            violations.add(new FieldViolation("page", ViolationCode.OUT_OF_RANGE));
        }
        if (size != null && (size < MIN_SIZE || size > MAX_SIZE)) {
            violations.add(new FieldViolation("size", ViolationCode.OUT_OF_RANGE));
        }
        return violations;
    }

    /** Index du premier élément de la page, calculé en {@code long} (pas de débordement). */
    public long offset() {
        return (long) page * size;
    }

    /** Index (exclu) du dernier élément de la page. */
    public long endExclusive() {
        return offset() + size;
    }
}
