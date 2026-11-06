package com.example.library.catalog.domain.model;

/** Violation d'une règle portant sur un champ donné. */
public record FieldViolation(String field, ViolationCode code) {
}
