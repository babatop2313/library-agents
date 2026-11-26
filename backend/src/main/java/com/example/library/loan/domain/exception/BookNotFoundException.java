package com.example.library.loan.domain.exception;

import java.util.Objects;

import com.example.library.loan.domain.model.BookId;

/** Aucun livre du catalogue ne porte l'identifiant demandé. */
public class BookNotFoundException extends DomainException {

    private final BookId bookId;

    public BookNotFoundException(BookId bookId) {
        super("BOOK_NOT_FOUND", "Aucun livre du catalogue ne correspond à cet identifiant");
        this.bookId = Objects.requireNonNull(bookId, "bookId");
    }

    public BookId getBookId() {
        return bookId;
    }
}
