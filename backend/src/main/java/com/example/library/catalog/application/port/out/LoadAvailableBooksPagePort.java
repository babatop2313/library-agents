package com.example.library.catalog.application.port.out;

import java.util.List;

import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.CatalogPageRequest;
import com.example.library.catalog.domain.model.SearchText;

public interface LoadAvailableBooksPagePort {

    /**
     * Livres ayant au moins un exemplaire disponible, filtrés selon {@link SearchText#matches(Book)},
     * dans l'ordre du catalogue (titre sans tenir compte de la casse, puis ISBN), à partir de
     * {@code pageRequest.offset()}, au plus {@code pageRequest.size()}.
     */
    List<Book> loadAvailableBooksPage(SearchText text, CatalogPageRequest pageRequest);
}
