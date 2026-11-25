package com.example.library.catalog.application.port.out;

import java.util.List;
import java.util.Set;

import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;

public interface LoadBooksByIdsPort {

    /** Les livres existants parmi {@code ids}, en une seule requête (jamais servie par le cache). */
    List<Book> loadByIds(Set<BookId> ids);
}
