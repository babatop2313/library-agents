package com.example.library.catalog.adapter.in.mcp;

/** Fiche d'un livre renvoyée à l'assistant. L'identifiant technique n'est volontairement pas exposé. */
record BookView(String isbn, String title, String author, int availableCopies, int totalCopies) {
}
