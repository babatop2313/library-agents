package com.example.library.catalog.adapter.out.persistence;

import com.example.library.catalog.application.port.out.CheckIsbnRegisteredPort;
import com.example.library.catalog.application.port.out.SaveBookPort;
import com.example.library.catalog.domain.exception.IsbnAlreadyExistsException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.Isbn;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
class BookPersistenceAdapter implements CheckIsbnRegisteredPort, SaveBookPort {

    static final String ISBN_UNIQUE_CONSTRAINT = "uk_book_isbn";

    private final BookJpaRepository repository;
    private final BookPersistenceMapper mapper;

    BookPersistenceAdapter(BookJpaRepository repository, BookPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public boolean isRegistered(Isbn isbn) {
        return repository.existsByIsbn(isbn.value());
    }

    @Override
    public void save(Book book) {
        try {
            // flush immédiat : la violation d'unicité (enregistrement concurrent) doit être détectée ici
            repository.saveAndFlush(mapper.toJpa(book));
        } catch (DataIntegrityViolationException e) {
            if (isIsbnUniqueViolation(e)) {
                throw new IsbnAlreadyExistsException(book.getIsbn());
            }
            throw e;
        }
    }

    private static boolean isIsbnUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof ConstraintViolationException cve
                    && ISBN_UNIQUE_CONSTRAINT.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
