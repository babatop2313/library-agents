package com.example.library.catalog.application.port.out;

import java.util.Optional;

import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.Isbn;

public interface LoadBookByIsbnPort {

    Optional<Book> loadByIsbn(Isbn isbn);
}
