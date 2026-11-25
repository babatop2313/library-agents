package com.example.library.catalog.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Contrat public du module catalogue pour les autres modules (JDK uniquement : aucun type du domaine).
 * Les opérations s'exécutent dans la transaction de l'appelant. Les cas attendus sont des résultats, jamais des
 * exceptions : une exception marquerait la transaction de l'appelant « rollback-only ».
 */
public interface CatalogApi {

    /** Raison : aucun livre ne porte cet identifiant. */
    String BOOK_NOT_FOUND = "BOOK_NOT_FOUND";

    /** Raison : plus aucun exemplaire disponible. */
    String NO_COPY_AVAILABLE = "NO_COPY_AVAILABLE";

    /** Prend un exemplaire disponible du livre (emprunt). */
    ReservationResult reserveCopy(UUID bookId);

    /** Restitue un exemplaire du livre (retour). */
    ReleaseResult releaseCopy(UUID bookId);

    /**
     * Les livres existants parmi les identifiants ; les identifiants inconnus sont absents.
     *
     * @throws IllegalArgumentException s'il y a plus de 50 identifiants distincts
     */
    List<BookSummary> findBooksByIds(Collection<UUID> bookIds);
}
