package com.example.library.catalog.application.port.out;

import java.util.List;

import com.example.library.catalog.domain.model.Book;

public interface LoadAvailableBooksPort {

    /**
     * Livres ayant au moins un exemplaire disponible, dans l'ordre du catalogue
     * (titre sans tenir compte de la casse, puis ISBN), au plus {@code maxResults}.
     */
    List<Book> loadAvailableBooks(int maxResults);
}
