package com.example.library.catalog.application.port.in;

import java.util.Objects;

import com.example.library.catalog.domain.model.CatalogSearch;

public record SearchAvailableBooksQuery(CatalogSearch search) {

    public SearchAvailableBooksQuery {
        Objects.requireNonNull(search, "search");
    }

    /** @throws com.example.library.catalog.domain.exception.InvalidCatalogSearchException si page, size ou q sont invalides */
    public static SearchAvailableBooksQuery of(Integer page, Integer size, String q) {
        return new SearchAvailableBooksQuery(CatalogSearch.of(page, size, q));
    }
}
