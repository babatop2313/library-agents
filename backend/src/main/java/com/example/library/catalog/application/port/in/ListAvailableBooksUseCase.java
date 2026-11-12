package com.example.library.catalog.application.port.in;

import java.util.List;

import com.example.library.catalog.domain.model.Book;

public interface ListAvailableBooksUseCase {

    /** Livres ayant au moins un exemplaire disponible, triés par titre puis ISBN, au plus la limite demandée. */
    List<Book> listAvailable(ListAvailableBooksQuery query);
}
