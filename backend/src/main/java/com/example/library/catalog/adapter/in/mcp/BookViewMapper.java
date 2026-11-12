package com.example.library.catalog.adapter.in.mcp;

import com.example.library.catalog.domain.model.Book;

final class BookViewMapper {

    private BookViewMapper() {
    }

    static BookView from(Book book) {
        return new BookView(
                book.getIsbn().value(),
                book.getTitle(),
                book.getAuthor(),
                book.getAvailableCopies(),
                book.getTotalCopies());
    }
}
