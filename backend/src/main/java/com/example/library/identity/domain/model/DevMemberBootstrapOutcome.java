package com.example.library.identity.domain.model;

/** Issue de l'amorçage du membre de test, en profil local (RM-LOAN-30). */
public enum DevMemberBootstrapOutcome {
    CREATED,
    MISSING_CONFIGURATION,
    INVALID_EMAIL,
    WEAK_PASSWORD,
    ALREADY_PRESENT,
    EMAIL_TAKEN_WITHOUT_MEMBER_ROLE
}
