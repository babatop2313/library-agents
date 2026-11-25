package com.example.library.catalog.domain.exception;

import java.util.Objects;

import com.example.library.catalog.domain.model.BookId;

/**
 * Restitution d'un exemplaire au-delà du total : invariant violé, donc incohérence de données.
 * Erreur technique (aucune traduction métier) : la transaction en cours est annulée.
 */
public class AvailableCopiesOverflowException extends DomainException {

    private final BookId bookId;

    public AvailableCopiesOverflowException(BookId bookId) {
        this(bookId, "Le nombre d'exemplaires disponibles dépasserait le total");
    }

    public AvailableCopiesOverflowException(BookId bookId, String message) {
        super("AVAILABLE_COPIES_OVERFLOW", message);
        this.bookId = Objects.requireNonNull(bookId, "bookId");
    }

    public BookId getBookId() {
        return bookId;
    }
}
