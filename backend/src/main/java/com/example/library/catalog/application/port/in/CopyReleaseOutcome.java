package com.example.library.catalog.application.port.in;

/** Issue d'une restitution d'exemplaire (issue plutôt qu'exception, voir {@link CopyReservationOutcome}). */
public enum CopyReleaseOutcome {
    RELEASED,
    BOOK_NOT_FOUND
}
