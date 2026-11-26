package com.example.library.catalog.application.port.in;

public interface ReleaseCopyUseCase {

    /**
     * Restitue un exemplaire du livre (retour d'un emprunt).
     *
     * @throws com.example.library.catalog.domain.exception.AvailableCopiesOverflowException si tous les exemplaires
     *         sont déjà disponibles : incohérence de données, erreur technique
     */
    CopyReleaseOutcome release(ReleaseCopyCommand command);
}
