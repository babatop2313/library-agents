package com.example.library.catalog.domain.service;

import com.example.library.catalog.domain.exception.IsbnAlreadyExistsException;
import com.example.library.catalog.domain.model.Isbn;

/** Règle d'unicité de l'ISBN (RM-07) : service de domaine sans état ni framework. */
public final class BookRegistrationPolicy {

    public void ensureIsbnIsFree(Isbn isbn, boolean alreadyRegistered) {
        if (alreadyRegistered) {
            throw new IsbnAlreadyExistsException(isbn);
        }
    }
}
