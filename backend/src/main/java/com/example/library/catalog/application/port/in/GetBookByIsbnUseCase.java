package com.example.library.catalog.application.port.in;

import com.example.library.catalog.domain.model.Book;

public interface GetBookByIsbnUseCase {

    /** @throws com.example.library.catalog.domain.exception.BookNotFoundException si aucun livre ne porte cet ISBN */
    Book getByIsbn(GetBookByIsbnQuery query);
}
