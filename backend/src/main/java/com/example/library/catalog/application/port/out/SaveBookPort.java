package com.example.library.catalog.application.port.out;

import com.example.library.catalog.domain.model.Book;

public interface SaveBookPort {

    /** @throws com.example.library.catalog.domain.exception.IsbnAlreadyExistsException en cas d'enregistrement concurrent du même ISBN */
    void save(Book book);
}
