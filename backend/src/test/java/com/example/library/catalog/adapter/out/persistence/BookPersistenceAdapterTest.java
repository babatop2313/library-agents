package com.example.library.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration;
import com.example.library.catalog.domain.service.AvailableBooksSelection;
import com.example.library.catalog.domain.exception.IsbnAlreadyExistsException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persistance reelle (PostgreSQL via Testcontainers, schema Flyway V1) : RM-07, RM-CAT-04, RM-CAT-08. */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestcontainersConfiguration.class, BookPersistenceAdapter.class, BookPersistenceMapper.class})
class BookPersistenceAdapterTest {

    @Autowired
    private BookPersistenceAdapter adapter;

    @Autowired
    private BookJpaRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM book");
    }

    @Test
    void contexte_quandSliceJpa_alorsDemarreSansRedis() {
        // Given : le slice JPA importe seulement PostgreSQL
        // When : on cherche un conteneur ou une connexion Redis dans le contexte
        // Then : aucun conteneur Redis de test, aucun conteneur générique, aucune fabrique de connexion Redis
        assertThat(context.containsBean("redisContainer")).isFalse();
        assertThat(context.getBeansOfType(GenericContainer.class).values())
                .extracting(container -> container.getDockerImageName())
                .doesNotContain(RedisTestcontainersConfiguration.REDIS_IMAGE);
        // classe chargée par réflexion : ce package de test n'importe pas spring-data-redis
        assertThat(context.getBeanNamesForType(redisConnectionFactoryType())).isEmpty();
    }

    private static Class<?> redisConnectionFactoryType() {
        try {
            return Class.forName("org.springframework.data.redis.connection.RedisConnectionFactory");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
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

    // ---- emprunts : lecture par identifiant et mise à jour atomique du stock (RM-04, RM-LOAN-31, 32)

    private Book givenSavedBook(String isbn, int total, int available) {
        Book book = Book.restore(BookId.newId(), Isbn.of(isbn), "Titre " + isbn, "Auteur", total, available);
        adapter.save(book);
        return book;
    }

    private int availableCopiesOf(Book book) {
        return jdbc.queryForObject("SELECT available_copies FROM book WHERE id = ?", Integer.class,
                book.getId().value());
    }

    private long versionOf(Book book) {
        return jdbc.queryForObject("SELECT version FROM book WHERE id = ?", Long.class, book.getId().value());
    }

    @Test
    void loadById_quandLivreSauvegarde_alorsPresentAvecTousLesChamps() {
        // Given
        Book book = givenSavedBook("9782070368228", 3, 2);
        entityManager.clear();
        // When
        Optional<Book> result = adapter.loadById(book.getId());
        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getIsbn().value()).isEqualTo("9782070368228");
        assertThat(result.get().getTotalCopies()).isEqualTo(3);
        assertThat(result.get().getAvailableCopies()).isEqualTo(2);
    }

    @Test
    void loadById_quandLivreSansExemplaireDisponible_alorsQuandMemePresent() {
        // Given : lecture directe, jamais filtrée sur la disponibilité
        Book book = givenSavedBook("9782070368228", 2, 0);
        // When / Then
        assertThat(adapter.loadById(book.getId())).isPresent();
    }

    @Test
    void loadById_quandIdentifiantInconnu_alorsAbsent() {
        // When / Then
        assertThat(adapter.loadById(BookId.newId())).isEmpty();
    }

    @Test
    void decrementAvailableCopies_quandUnExemplaireDisponible_alorsTrueDisponibleAZeroPuisFaux() {
        // Given
        Book book = givenSavedBook("9782070368228", 3, 1);
        long versionBefore = versionOf(book);
        // When
        boolean first = adapter.decrementAvailableCopies(book.getId());
        boolean second = adapter.decrementAvailableCopies(book.getId());
        // Then : RM-LOAN-31, la condition SQL arrête la décrémentation à 0 sans erreur
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(availableCopiesOf(book)).isZero();
        assertThat(jdbc.queryForObject("SELECT total_copies FROM book WHERE id = ?", Integer.class,
                book.getId().value())).isEqualTo(3);
        assertThat(versionOf(book)).isEqualTo(versionBefore + 1);
    }

    @Test
    void decrementAvailableCopies_quandLivreInconnu_alorsFaux() {
        // When / Then
        assertThat(adapter.decrementAvailableCopies(BookId.newId())).isFalse();
    }

    @Test
    void decrementAvailableCopies_quandDeuxExemplaires_alorsUnSeulRetire() {
        // Given
        Book book = givenSavedBook("9782070368228", 3, 2);
        // When
        adapter.decrementAvailableCopies(book.getId());
        // Then
        assertThat(availableCopiesOf(book)).isEqualTo(1);
    }

    @Test
    void incrementAvailableCopies_quandUnExemplaireEmprunte_alorsTrueEtDisponiblesIncrementes() {
        // Given
        Book book = givenSavedBook("9782070368228", 3, 2);
        long versionBefore = versionOf(book);
        // When
        boolean changed = adapter.incrementAvailableCopies(book.getId());
        // Then
        assertThat(changed).isTrue();
        assertThat(availableCopiesOf(book)).isEqualTo(3);
        assertThat(versionOf(book)).isEqualTo(versionBefore + 1);
    }

    @Test
    void incrementAvailableCopies_quandTousExemplairesDisponibles_alorsFauxEtStockInchange() {
        // Given : borné par total_copies
        Book book = givenSavedBook("9782070368228", 3, 3);
        long versionBefore = versionOf(book);
        // When
        boolean changed = adapter.incrementAvailableCopies(book.getId());
        // Then
        assertThat(changed).isFalse();
        assertThat(availableCopiesOf(book)).isEqualTo(3);
        assertThat(versionOf(book)).isEqualTo(versionBefore);
    }

    @Test
    void incrementAvailableCopies_quandLivreInconnu_alorsFaux() {
        // When / Then
        assertThat(adapter.incrementAvailableCopies(BookId.newId())).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void migration_quandEcritureDirecteDisponiblesNegatifs_alorsRefuseParCkBookAvailableCopiesRange() {
        // Given : un livre dont availableCopies vaut 0 (RM-LOAN-31, double garantie)
        Book book = givenSavedBook("9782070368228", 2, 0);
        // When / Then : la contrainte de base est conservée
        assertThatThrownBy(() -> jdbc.update("UPDATE book SET available_copies = -1 WHERE id = ?", book.getId().value()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_book_available_copies_range");
        assertThat(availableCopiesOf(book)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void migration_quandEcritureDirecteAuDessusDuTotal_alorsRefuseParCkBookAvailableCopiesRange() {
        // Given
        Book book = givenSavedBook("9782070368228", 2, 2);
        // When / Then : total_copies + 1
        assertThatThrownBy(() -> jdbc.update("UPDATE book SET available_copies = 3 WHERE id = ?", book.getId().value()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_book_available_copies_range");
        assertThat(availableCopiesOf(book)).isEqualTo(2);
    }

    @Test
    void loadByIds_quandTroisIdentifiants_alorsTroisLivresEnUneSeuleRequeteSql() {
        // Given
        Book a = givenSavedBook("9782070368228", 1, 1);
        Book b = givenSavedBook("9782070368235", 1, 0);
        Book c = givenSavedBook("9782070368242", 2, 1);
        givenSavedBook("9782070368259", 1, 1);
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        // When
        List<Book> result = adapter.loadByIds(Set.of(a.getId(), b.getId(), c.getId()));
        // Then : RM-LOAN-32, une seule requête WHERE id IN (...), livres épuisés inclus
        assertThat(result).extracting(Book::getId).containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void loadByIds_quandIdentifiantsInconnus_alorsAbsentsDuResultat() {
        // Given
        Book a = givenSavedBook("9782070368228", 1, 1);
        // When
        List<Book> result = adapter.loadByIds(Set.of(a.getId(), BookId.newId()));
        // Then
        assertThat(result).extracting(Book::getId).containsExactly(a.getId());
    }
}
