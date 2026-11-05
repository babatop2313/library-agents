package com.example.library.catalog.domain.exception;

import java.util.List;

import com.example.library.catalog.domain.model.FieldViolation;

/** Données d'un livre invalides ; porte la liste complète des violations. */
public class InvalidBookException extends DomainException {

    private final List<FieldViolation> violations;

    public InvalidBookException(List<FieldViolation> violations) {
        super("VALIDATION_FAILED", "Les données du livre sont invalides");
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
