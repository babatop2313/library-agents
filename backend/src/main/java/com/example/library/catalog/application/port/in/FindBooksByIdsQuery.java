package com.example.library.catalog.application.port.in;

import java.util.Objects;
import java.util.Set;

import com.example.library.catalog.domain.model.BookId;

/** Lot d'identifiants, borné : il protège la clause {@code IN} (le module d'emprunts n'en envoie jamais plus de 3). */
public record FindBooksByIdsQuery(Set<BookId> ids) {

    public static final int MAX_IDS = 50;

    public FindBooksByIdsQuery {
        Objects.requireNonNull(ids, "ids");
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("Au plus " + MAX_IDS + " identifiants par demande");
        }
        ids = Set.copyOf(ids);
    }
}
