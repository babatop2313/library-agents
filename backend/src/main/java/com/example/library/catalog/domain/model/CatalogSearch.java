package com.example.library.catalog.domain.model;

import java.util.ArrayList;
import java.util.List;

import com.example.library.catalog.domain.exception.InvalidCatalogSearchException;

/** Consultation du catalogue : texte de recherche et page demandée. */
public record CatalogSearch(SearchText text, CatalogPageRequest pageRequest) {

    /** Réunit toutes les violations (page, size, q) en une seule passe avant de lever l'exception. */
    public static CatalogSearch of(Integer page, Integer size, String q) {
        List<FieldViolation> violations = new ArrayList<>();
        violations.addAll(CatalogPageRequest.violationsOf(page, size));
        violations.addAll(SearchText.violationsOf(q));
        if (!violations.isEmpty()) {
            throw new InvalidCatalogSearchException(violations);
        }
        return new CatalogSearch(SearchText.of(q), CatalogPageRequest.of(page, size));
    }
}
