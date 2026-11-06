package com.example.library.catalog.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identifiant métier d'un livre. */
public record BookId(UUID value) {

    public BookId {
        Objects.requireNonNull(value, "value");
    }

    public static BookId newId() {
        return new BookId(UUID.randomUUID());
    }
}
