package com.example.library.catalog.application.port.out;

import com.example.library.catalog.domain.model.SearchText;

public interface CountAvailableBooksPort {

    /** Nombre de livres ayant au moins un exemplaire disponible et correspondant au texte (même filtre que la page). */
    long countAvailableBooks(SearchText text);
}
