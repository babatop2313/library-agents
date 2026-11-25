package com.example.library.loan.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identifiant métier d'un emprunt. */
public record LoanId(UUID value) {

    public LoanId {
        Objects.requireNonNull(value, "value");
    }

    public static LoanId newId() {
        return new LoanId(UUID.randomUUID());
    }
}
