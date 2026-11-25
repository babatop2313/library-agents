package com.example.library.catalog.application.port.in;

import java.util.List;

import com.example.library.catalog.domain.model.Book;

public interface FindBooksByIdsUseCase {

    /** Les livres existants parmi les identifiants demandés (les inconnus sont absents du résultat). */
    List<Book> findByIds(FindBooksByIdsQuery query);
}
