package com.example.library.catalog.application.port.out;

import java.util.Optional;

import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;

public interface LoadBookByIdPort {

    /** Lecture directe en base (jamais servie par le cache). */
    Optional<Book> loadById(BookId id);
}
