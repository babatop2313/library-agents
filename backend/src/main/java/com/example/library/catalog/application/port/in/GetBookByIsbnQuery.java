package com.example.library.catalog.application.port.in;

import java.util.Objects;

import com.example.library.catalog.domain.model.Isbn;

public record GetBookByIsbnQuery(Isbn isbn) {

    public GetBookByIsbnQuery {
        Objects.requireNonNull(isbn, "isbn");
    }

    /** Normalise et valide l'ISBN brut (RM-07) ; lève InvalidIsbnException s'il est invalide. */
    public static GetBookByIsbnQuery fromRaw(String rawIsbn) {
        return new GetBookByIsbnQuery(Isbn.of(rawIsbn));
    }
}
