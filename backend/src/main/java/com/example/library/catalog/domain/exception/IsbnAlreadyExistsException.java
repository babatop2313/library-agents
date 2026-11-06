package com.example.library.catalog.domain.exception;

import com.example.library.catalog.domain.model.Isbn;

/** Un livre avec cet ISBN est déjà enregistré (RM-07). */
public class IsbnAlreadyExistsException extends DomainException {

    private final Isbn isbn;

    public IsbnAlreadyExistsException(Isbn isbn) {
        super("ISBN_ALREADY_EXISTS", "Un livre avec cet ISBN existe déjà");
        this.isbn = isbn;
    }

    public Isbn getIsbn() {
        return isbn;
    }
}
