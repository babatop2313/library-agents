package com.example.library.catalog.domain.exception;

import java.util.Objects;

import com.example.library.catalog.domain.model.BookId;

/** Aucun exemplaire disponible : le livre ne peut pas être emprunté (RM-04). */
public class NoCopyAvailableException extends DomainException {

    private final BookId bookId;

    public NoCopyAvailableException(BookId bookId) {
        super("BOOK_NOT_AVAILABLE", "Aucun exemplaire de ce livre n'est disponible");
        this.bookId = Objects.requireNonNull(bookId, "bookId");
    }

    public BookId getBookId() {
        return bookId;
    }
}
