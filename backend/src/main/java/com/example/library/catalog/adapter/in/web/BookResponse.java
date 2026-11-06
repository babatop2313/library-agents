package com.example.library.catalog.adapter.in.web;

import java.util.UUID;

record BookResponse(UUID id, String isbn, String title, String author, int totalCopies, int availableCopies) {
}
