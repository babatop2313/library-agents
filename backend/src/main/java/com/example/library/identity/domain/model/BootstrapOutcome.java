package com.example.library.identity.domain.model;

/** Issue de l'amorçage du premier bibliothécaire (RM-AUTH-08). */
public enum BootstrapOutcome {
    CREATED,
    LIBRARIAN_ALREADY_PRESENT,
    MISSING_CONFIGURATION,
    INVALID_EMAIL,
    WEAK_PASSWORD,
    EMAIL_TAKEN_BY_NON_LIBRARIAN
}
