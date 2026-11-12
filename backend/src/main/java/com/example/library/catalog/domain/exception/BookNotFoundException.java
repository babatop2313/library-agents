package com.example.library.catalog.domain.exception;

import java.util.Objects;

import com.example.library.catalog.domain.model.Isbn;

/** Aucun livre du catalogue ne porte l'ISBN demandé. */
public class BookNotFoundException extends DomainException {

    private final Isbn isbn;

    public BookNotFoundException(Isbn isbn) {
        super("BOOK_NOT_FOUND", "Aucun livre trouvé pour cet ISBN");
        this.isbn = Objects.requireNonNull(isbn, "isbn");
    }

    public Isbn getIsbn() {
        return isbn;
    }
}
