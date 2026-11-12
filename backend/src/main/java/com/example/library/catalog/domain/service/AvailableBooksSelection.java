package com.example.library.catalog.domain.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.example.library.catalog.domain.model.AvailableBooksLimit;
import com.example.library.catalog.domain.model.Book;

/** Sélection des livres empruntables : filtre (RM-04), tri déterministe, troncature à la limite. */
public final class AvailableBooksSelection {

    /** Titre sans tenir compte de la casse, puis ISBN. */
    public static final Comparator<Book> CATALOG_ORDER = Comparator
            .comparing((Book book) -> book.getTitle().toLowerCase(Locale.ROOT))
            .thenComparing(book -> book.getIsbn().value());

    public List<Book> select(List<Book> candidates, AvailableBooksLimit limit) {
        return candidates.stream()
                .filter(Book::isAvailable)
                .sorted(CATALOG_ORDER)
                .limit(limit.value())
                .toList();
    }
}
