package com.example.library.catalog.adapter.in.web;

import com.example.library.catalog.domain.model.CatalogPageRequest;
import com.example.library.catalog.domain.model.SearchText;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Paramètres de requête du GET /api/v1/books ; absent ou vide = valeur par défaut (null). */
record SearchBooksParams(
        @Min(CatalogPageRequest.MIN_PAGE) Integer page,
        @Min(CatalogPageRequest.MIN_SIZE) @Max(CatalogPageRequest.MAX_SIZE) Integer size,
        @MaxTrimmedLength(max = SearchText.MAX_LENGTH) String q) {
}
