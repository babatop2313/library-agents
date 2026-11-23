package com.example.library.catalog.adapter.in.web;

import com.example.library.catalog.application.port.in.RegisterBookCommand;
import com.example.library.catalog.application.port.in.SearchAvailableBooksQuery;
import com.example.library.catalog.domain.model.AvailableBooksPage;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.Isbn;

final class BookWebMapper {

    private BookWebMapper() {
    }

    static RegisterBookCommand toCommand(RegisterBookRequest request) {
        return new RegisterBookCommand(Isbn.of(request.isbn()), request.title(), request.author(), request.copies());
    }

    static SearchAvailableBooksQuery toQuery(SearchBooksParams params) {
        return SearchAvailableBooksQuery.of(params.page(), params.size(), params.q());
    }

    static BookPageResponse toPageResponse(AvailableBooksPage page) {
        return new BookPageResponse(page.content().stream().map(BookWebMapper::toResponse).toList(),
                page.pageRequest().page(), page.pageRequest().size(), page.totalElements(), page.totalPages());
    }

    static BookResponse toResponse(Book book) {
        return new BookResponse(book.getId().value(), book.getIsbn().value(), book.getTitle(), book.getAuthor(),
                book.getTotalCopies(), book.getAvailableCopies());
    }
}
