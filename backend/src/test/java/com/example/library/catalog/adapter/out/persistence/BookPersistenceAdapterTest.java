package com.example.library.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import com.example.library.TestcontainersConfiguration;
import com.example.library.catalog.domain.service.AvailableBooksSelection;
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

    private void givenBook(String isbn, String title, int total, int available) {
        adapter.save(Book.restore(BookId.newId(), Isbn.of(isbn), title, "Auteur", total, available));
    }

    private static List<String> isbns(List<Book> books) {
        return books.stream().map(b -> b.getIsbn().value()).toList();
    }

    @Test
    void loadByIsbn_quandLivreSauvegarde_alorsPresentAvecTousLesChamps() {
        // Given
        givenBook("9782070368228", "L'Étranger", 3, 2);
        // When
        Optional<Book> result = adapter.loadByIsbn(Isbn.of("978-2-07-036822-8"));
        // Then
        assertThat(result).isPresent();
        Book book = result.get();
        assertThat(book.getIsbn().value()).isEqualTo("9782070368228");
        assertThat(book.getTitle()).isEqualTo("L'Étranger");
        assertThat(book.getAuthor()).isEqualTo("Auteur");
        assertThat(book.getTotalCopies()).isEqualTo(3);
        assertThat(book.getAvailableCopies()).isEqualTo(2);
    }

    @Test
    void loadByIsbn_quandIsbnInconnu_alorsAbsent() {
        // Given
        givenBook("9782070368228", "T", 1, 1);
        // When / Then
        assertThat(adapter.loadByIsbn(Isbn.of("9780000000002"))).isEmpty();
    }

    @Test
    void loadByIsbn_quandLivreSansExemplaireDisponible_alorsQuandMemePresent() {
        // Given
        givenBook("9782070368228", "T", 2, 0);
        // When / Then
        assertThat(adapter.loadByIsbn(Isbn.of("9782070368228"))).isPresent();
    }

    @Test
    void loadAvailableBooks_quandLivresEpuises_alorsExclus() {
        // Given
        givenBook("9782070368228", "Dispo", 2, 1);
        givenBook("9782070368235", "Epuise", 2, 0);
        // When
        List<Book> result = adapter.loadAvailableBooks(10);
        // Then
        assertThat(isbns(result)).containsExactly("9782070368228");
    }

    @Test
    void loadAvailableBooks_quandCasseMixteEtAccents_alorsOrdreIdentiqueAuDomaine() {
        // Given
        givenBook("9782070368228", "banane", 1, 1);
        givenBook("9782070368235", "Abricot", 1, 1);
        givenBook("9782070368242", "zèbre", 1, 1);
        givenBook("9782070368259", "Éclair", 1, 1);
        givenBook("9782070368266", "apple", 1, 1);
        givenBook("9782070368273", "ZEBU", 1, 1);
        givenBook("9782070368280", "Ça va", 1, 1);
        // When
        List<Book> result = adapter.loadAvailableBooks(20);
        // Then : meme ordre que CATALOG_ORDER (alignement SQL / Java)
        List<Book> expected = result.stream().sorted(AvailableBooksSelection.CATALOG_ORDER).toList();
        assertThat(isbns(result)).hasSize(7).containsExactlyElementsOf(isbns(expected));
    }

    @Test
    void loadAvailableBooks_quandMemeTitreCasseDifferente_alorsDepartageParIsbn() {
        // Given
        givenBook("9782070368242", "Roman", 1, 1);
        givenBook("9782070368228", "ROMAN", 1, 1);
        givenBook("9782070368235", "roman", 1, 1);
        // When
        List<Book> result = adapter.loadAvailableBooks(10);
        // Then
        assertThat(isbns(result)).containsExactly("9782070368228", "9782070368235", "9782070368242");
    }

    @Test
    void loadAvailableBooks_quandLimiteInferieureAuNombre_alorsTronque() {
        // Given
        givenBook("9782070368228", "C", 1, 1);
        givenBook("9782070368235", "A", 1, 1);
        givenBook("9782070368242", "B", 1, 1);
        // When
        List<Book> result = adapter.loadAvailableBooks(2);
        // Then
        assertThat(result).extracting(Book::getTitle).containsExactly("A", "B");
    }

    @Test
    void loadAvailableBooks_quandCatalogueVide_alorsListeVide() {
        // When / Then
        assertThat(adapter.loadAvailableBooks(10)).isEmpty();
    }

    @Test
    void loadAvailableBooks_quandConsultation_alorsAucuneEcritureVersionInchangee() {
        // Given
        givenBook("9782070368228", "A", 2, 1);
        Long before = jdbc.queryForObject("SELECT version FROM book", Long.class);
        // When
        adapter.loadAvailableBooks(10);
        adapter.loadByIsbn(Isbn.of("9782070368228"));
        // Then
        assertThat(jdbc.queryForObject("SELECT version FROM book", Long.class)).isEqualTo(before);
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
