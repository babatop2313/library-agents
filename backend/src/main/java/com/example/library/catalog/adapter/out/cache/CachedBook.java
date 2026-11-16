package com.example.library.catalog.adapter.out.cache;

/** Représentation d'un livre dans la valeur JSON du cache (format lié au suffixe de clé v1, RC-10). */
record CachedBook(String id, String isbn, String title, String author, int totalCopies, int availableCopies) {
}
