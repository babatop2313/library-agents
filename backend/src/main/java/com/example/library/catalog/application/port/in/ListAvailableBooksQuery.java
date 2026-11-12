package com.example.library.catalog.application.port.in;

import java.util.Objects;

import com.example.library.catalog.domain.model.AvailableBooksLimit;

public record ListAvailableBooksQuery(AvailableBooksLimit limit) {

    public ListAvailableBooksQuery {
        Objects.requireNonNull(limit, "limit");
    }

    /** Limite facultative : valeur par défaut si absente, LimitOutOfRangeException si hors bornes. */
    public static ListAvailableBooksQuery of(Integer requestedLimit) {
        return new ListAvailableBooksQuery(AvailableBooksLimit.of(requestedLimit));
    }
}
