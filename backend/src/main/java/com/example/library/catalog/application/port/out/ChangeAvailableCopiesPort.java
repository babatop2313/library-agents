package com.example.library.catalog.application.port.out;

import com.example.library.catalog.domain.model.BookId;

/** Modification atomique du compteur d'exemplaires disponibles (emprunt, retour). */
@AffectsAvailableBooks
public interface ChangeAvailableCopiesPort {

    /** Retire un exemplaire disponible ; {@code false} si aucune ligne n'a été modifiée (plus d'exemplaire, ou livre absent). */
    boolean decrementAvailableCopies(BookId id);

    /** Ajoute un exemplaire disponible, sans dépasser le total ; {@code false} si aucune ligne n'a été modifiée. */
    boolean incrementAvailableCopies(BookId id);
}
