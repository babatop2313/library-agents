package com.example.library.catalog.adapter.out.cache;

import java.util.UUID;

import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;

/** Conversion Book / CachedBook. La reconstruction passe par {@code Book.restore}, jamais {@code Book.register} (D9). */
final class CachedBookMapper {

    CachedBook toCache(Book book) {
        return new CachedBook(book.getId().value().toString(), book.getIsbn().value(), book.getTitle(),
                book.getAuthor(), book.getTotalCopies(), book.getAvailableCopies());
    }

    Book toDomain(CachedBook cached) {
        return Book.restore(new BookId(UUID.fromString(cached.id())), new Isbn(cached.isbn()), cached.title(),
                cached.author(), cached.totalCopies(), cached.availableCopies());
    }
}
