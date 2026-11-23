package com.example.library.catalog.domain.exception;

import java.util.List;

import com.example.library.catalog.domain.model.FieldViolation;

/** Paramètres de consultation du catalogue invalides (page, size, q) ; porte la liste complète des violations. */
public class InvalidCatalogSearchException extends DomainException {

    private final List<FieldViolation> violations;

    public InvalidCatalogSearchException(List<FieldViolation> violations) {
        super("VALIDATION_FAILED", "Les paramètres de recherche du catalogue sont invalides");
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
