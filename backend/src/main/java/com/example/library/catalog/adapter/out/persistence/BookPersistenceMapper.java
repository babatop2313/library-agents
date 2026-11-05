package com.example.library.catalog.adapter.out.persistence;

import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import org.springframework.stereotype.Component;

@Component
class BookPersistenceMapper {

    /** Un livre nouvellement enregistré n'a pas encore de version : JPA fera un persist. */
    BookJpaEntity toJpa(Book book) {
        return new BookJpaEntity(book.getId().value(), book.getIsbn().value(), book.getTitle(),
                book.getAuthor(), book.getTotalCopies(), book.getAvailableCopies(), null);
    }

    Book toDomain(BookJpaEntity entity) {
        return Book.restore(new BookId(entity.getId()), new Isbn(entity.getIsbn()), entity.getTitle(),
                entity.getAuthor(), entity.getTotalCopies(), entity.getAvailableCopies());
    }
}
