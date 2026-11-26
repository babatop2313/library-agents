package com.example.library.catalog.application.port.in;

/**
 * Issue d'une demande de prise d'exemplaire. Les cas attendus sont des issues, jamais des exceptions : une exception
 * qui traverse le proxy transactionnel d'un use case marquerait « rollback-only » la transaction de l'appelant.
 */
public enum CopyReservationOutcome {
    RESERVED,
    BOOK_NOT_FOUND,
    NO_COPY_AVAILABLE
}
