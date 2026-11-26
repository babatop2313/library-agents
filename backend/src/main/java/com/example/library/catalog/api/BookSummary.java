package com.example.library.catalog.api;

import java.util.UUID;

/** Fiche minimale d'un livre pour les autres modules (aucun compteur d'exemplaires). */
public record BookSummary(UUID id, String isbn, String title, String author) {
}
