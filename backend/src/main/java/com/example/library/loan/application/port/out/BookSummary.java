package com.example.library.loan.application.port.out;

import com.example.library.loan.domain.model.BookId;

/** Informations d'un livre utiles à l'affichage d'un emprunt. */
public record BookSummary(BookId id, String isbn, String title, String author) {
}
