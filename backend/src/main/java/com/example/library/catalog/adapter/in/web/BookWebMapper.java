package com.example.library.catalog.adapter.in.web;

import com.example.library.catalog.application.port.in.RegisterBookCommand;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.Isbn;

final class BookWebMapper {

    private BookWebMapper() {
    }

    static RegisterBookCommand toCommand(RegisterBookRequest request) {
        return new RegisterBookCommand(Isbn.of(request.isbn()), request.title(), request.author(), request.copies());
    }

    static BookResponse toResponse(Book book) {
        return new BookResponse(book.getId().value(), book.getIsbn().value(), book.getTitle(), book.getAuthor(),
                book.getTotalCopies(), book.getAvailableCopies());
    }
}
