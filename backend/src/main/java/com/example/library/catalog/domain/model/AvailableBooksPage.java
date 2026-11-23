package com.example.library.catalog.domain.model;

import java.util.List;

/** Page de livres disponibles avec le total des livres correspondants (RM-CAT-18, 19, 20). */
public record AvailableBooksPage(List<Book> content, CatalogPageRequest pageRequest, long totalElements) {

    public AvailableBooksPage {
        content = List.copyOf(content);
    }

    public static AvailableBooksPage empty(CatalogPageRequest pageRequest, long totalElements) {
        return new AvailableBooksPage(List.of(), pageRequest, totalElements);
    }

    /** {@code ceil(total / size)} ; 0 si aucun résultat. */
    public int totalPages() {
        if (totalElements == 0) {
            return 0;
        }
        return (int) ((totalElements + pageRequest.size() - 1) / pageRequest.size());
    }
}
