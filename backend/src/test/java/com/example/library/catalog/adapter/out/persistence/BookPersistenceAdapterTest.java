package com.example.library.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.library.TestcontainersConfiguration;
import com.example.library.catalog.domain.exception.IsbnAlreadyExistsException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persistance reelle (PostgreSQL via Testcontainers, schema Flyway V1) : RM-07, RM-CAT-04, RM-CAT-08. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, BookPersistenceAdapter.class, BookPersistenceMapper.class})
class BookPersistenceAdapterTest {

    @Autowired
    private BookPersistenceAdapter adapter;

    @Autowired
    private BookJpaRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM book");
    }

    @Test
    void save_quandLivreNeuf_alorsRelectureIdentique() {
        // Given
        Book book = Book.register(Isbn.of("9782070368228"), "L'Étranger", "Albert Camus", 3);
        // When
        adapter.save(book);
        BookJpaEntity entity = repository.findById(book.getId().value()).orElseThrow();
        Book reloaded = new BookPersistenceMapper().toDomain(entity);
        // Then
        assertThat(reloaded.getId()).isEqualTo(book.getId());
        assertThat(reloaded.getIsbn().value()).isEqualTo("9782070368228");
        assertThat(reloaded.getTitle()).isEqualTo("L'Étranger");
        assertThat(reloaded.getAuthor()).isEqualTo("Albert Camus");
        assertThat(reloaded.getTotalCopies()).isEqualTo(3);
        assertThat(reloaded.getAvailableCopies()).isEqualTo(3);
        assertThat(entity.getVersion()).isNotNull();
    }

    @Test
    void isRegistered_quandIsbnSauvegarde_alorsVrai() {
        // Given
        adapter.save(Book.register(Isbn.of("9782070368228"), "T", "A", 1));
        // When / Then
        assertThat(adapter.isRegistered(Isbn.of("9782070368228"))).isTrue();
    }

    @Test
    void isRegistered_quandIsbnSousFormeAvecTirets_alorsVrai() {
        // Given
        adapter.save(Book.register(Isbn.of("9782070368228"), "T", "A", 1));
        // When / Then : unicite sur forme normalisee
        assertThat(adapter.isRegistered(Isbn.of("978-2-07-036822-8"))).isTrue();
    }

    @Test
    void isRegistered_quandIsbnInconnu_alorsFaux() {
        // When / Then
        assertThat(adapter.isRegistered(Isbn.of("9782070368228"))).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void save_quandMemeIsbnDejaPresent_alorsIsbnAlreadyExistsEtLivreExistantInchange() {
        // Given : un livre de 3 exemplaires deja enregistre (transactions reelles, sans rollback de test)
        Isbn isbn = Isbn.of("9782070368228");
        Book first = Book.register(isbn, "Premier", "Auteur", 3);
        adapter.save(first);
        Book duplicate = Book.register(isbn, "Second", "Auteur", 5);
        // When / Then
        assertThatThrownBy(() -> adapter.save(duplicate))
                .isInstanceOf(IsbnAlreadyExistsException.class)
                .extracting("code").isEqualTo("ISBN_ALREADY_EXISTS");
        assertThat(repository.count()).isEqualTo(1);
        BookJpaEntity kept = repository.findById(first.getId().value()).orElseThrow();
        assertThat(kept.getTotalCopies()).isEqualTo(3);
        assertThat(kept.getTitle()).isEqualTo("Premier");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void save_quandViolationAutreQueIsbn_alorsDataIntegrityViolationRelancee() {
        // Given : un livre dont le compteur disponible depasse le total (CHECK en base), non lie a l'ISBN
        Book inconsistent = Book.restore(BookId.newId(), Isbn.of("9782070368228"), "T", "A", 1, 5);
        // When / Then
        assertThatThrownBy(() -> adapter.save(inconsistent))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(IsbnAlreadyExistsException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void migration_quandInsertionIsbnMalFormeEnSql_alorsRefuseParContrainteCheck() {
        // When / Then : defense en profondeur du schema V1
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO book (id, isbn, title, author, total_copies, available_copies, version)"
                        + " VALUES (gen_random_uuid(), '978-2', 't', 'a', 1, 1, 0)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void migration_quandTotalCopiesZeroEnSql_alorsRefuseParContrainteCheck() {
        // When / Then
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO book (id, isbn, title, author, total_copies, available_copies, version)"
                        + " VALUES (gen_random_uuid(), '9782070368228', 't', 'a', 0, 0, 0)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
