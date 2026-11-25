package com.example.library.loan.domain.exception;

import java.util.Objects;

import com.example.library.loan.domain.model.BookId;

/** Aucun exemplaire du livre n'est disponible (RM-04). */
public class BookNotAvailableException extends DomainException {

    private final BookId bookId;

    public BookNotAvailableException(BookId bookId) {
        super("BOOK_NOT_AVAILABLE", "Aucun exemplaire de ce livre n'est disponible");
        this.bookId = Objects.requireNonNull(bookId, "bookId");
    }

    public BookId getBookId() {
        return bookId;
    }
}
