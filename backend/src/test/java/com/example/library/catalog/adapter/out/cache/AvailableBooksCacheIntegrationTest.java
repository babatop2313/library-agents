package com.example.library.catalog.adapter.out.cache;

import static com.example.library.catalog.adapter.out.cache.AvailableBooksCacheAdapter.KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration.RedisTestCache;
import com.example.library.catalog.application.port.in.ListAvailableBooksQuery;
import com.example.library.catalog.application.port.in.ListAvailableBooksUseCase;
import com.example.library.catalog.application.port.in.RegisterBookCommand;
import com.example.library.catalog.application.port.in.RegisterBookUseCase;
import com.example.library.catalog.application.port.out.CheckIsbnRegisteredPort;
import com.example.library.catalog.application.port.out.LoadAvailableBooksPort;
import com.example.library.catalog.application.port.out.LoadBookByIsbnPort;
import com.example.library.catalog.application.port.out.SaveBookPort;
import com.example.library.catalog.domain.exception.IsbnAlreadyExistsException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Câblage et invalidation du cache dans le contexte complet : vrai PostgreSQL, vrai Redis, vrai gestionnaire de
 * transactions JPA. Couvre RC-05, RC-13, RC-16.
 */
@SpringBootTest
@Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
class AvailableBooksCacheIntegrationTest {

    private static final String ISBN = "9782070368228";

    @Autowired
    private LoadAvailableBooksPort loadAvailableBooksPort;

    @Autowired
    private SaveBookPort saveBookPort;

    @Autowired
    private CheckIsbnRegisteredPort checkIsbnRegisteredPort;

    @Autowired
    private LoadBookByIsbnPort loadBookByIsbnPort;

    @Autowired
    @Qualifier("bookPersistenceAdapter")
    private LoadAvailableBooksPort persistenceLoad;

    @Autowired
    private RegisterBookUseCase registerBookUseCase;

    @Autowired
    private ListAvailableBooksUseCase listAvailableBooksUseCase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RedisTestCache redisTestCache;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM book");
        redisTestCache.flushAll();
    }

    @AfterEach
    void cleanAfter() {
        jdbc.update("DELETE FROM book");
        redisTestCache.flushAll();
    }

    private int countBooks(String isbn) {
        return jdbc.queryForObject("SELECT count(*) FROM book WHERE isbn = ?", Integer.class, isbn);
    }

    private void givenCachedList() {
        redis.opsForValue().set(KEY, "[]", Duration.ofMinutes(5));
    }

    @Test
    void contexte_quandCacheActif_alorsPortsLectureEtSauvegardeSontLeDecorateur() {
        // Given : le contexte complet (RC-05)
        // When / Then : les deux ports décorés fournissent le décorateur
        assertThat(loadAvailableBooksPort).isInstanceOf(AvailableBooksCacheAdapter.class);
        assertThat(saveBookPort).isInstanceOf(AvailableBooksCacheAdapter.class);
        // et le décorateur reçoit l'adapter de persistance, pas lui-même
        Object delegateLoad = ReflectionTestUtils.getField(loadAvailableBooksPort, "persistentLoad");
        Object delegateSave = ReflectionTestUtils.getField(saveBookPort, "persistentSave");
        assertThat(delegateLoad).isNotNull().isNotInstanceOf(AvailableBooksCacheAdapter.class);
        assertThat(delegateLoad.getClass().getSimpleName()).isEqualTo("BookPersistenceAdapter");
        assertThat(delegateSave).isSameAs(delegateLoad).isSameAs(persistenceLoad);
        // les ports hors cache restent sur la persistance
        assertThat(checkIsbnRegisteredPort).isNotInstanceOf(AvailableBooksCacheAdapter.class);
        assertThat(loadBookByIsbnPort).isNotInstanceOf(AvailableBooksCacheAdapter.class);
        assertThat(checkIsbnRegisteredPort.getClass().getSimpleName()).isEqualTo("BookPersistenceAdapter");
        assertThat(loadBookByIsbnPort.getClass().getSimpleName()).isEqualTo("BookPersistenceAdapter");
    }

    @Test
    void save_quandTransactionCommitee_alorsCleSupprimeeApresCommitEtPasAvant() {
        // Given : une liste en cache et une vraie transaction
        givenCachedList();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Book book = Book.register(Isbn.of(ISBN), "L'Étranger", "Albert Camus", 3);
        // When
        Boolean keyBeforeCommit = transaction.execute(status -> {
            saveBookPort.save(book);
            return redis.hasKey(KEY);
        });
        // Then : RC-13, la clé existait encore avant le commit et a disparu après
        assertThat(keyBeforeCommit).isTrue();
        assertThat(redis.hasKey(KEY)).isFalse();
        assertThat(countBooks(ISBN)).isEqualTo(1);
    }

    @Test
    void save_quandTransactionAnnuleeApresSauvegarde_alorsCleConserveeEtLivreAbsent() {
        // Given
        givenCachedList();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Book book = Book.register(Isbn.of(ISBN), "L'Étranger", "Albert Camus", 3);
        // When : échec après la sauvegarde, la transaction est annulée
        transaction.executeWithoutResult(status -> {
            saveBookPort.save(book);
            status.setRollbackOnly();
        });
        // Then : RC-13, ni commit ni invalidation
        assertThat(redis.hasKey(KEY)).isTrue();
        assertThat(countBooks(ISBN)).isZero();
    }

    @Test
    void save_quandExceptionApresSauvegarde_alorsRollbackEtCleConservee() {
        // Given
        givenCachedList();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Book book = Book.register(Isbn.of(ISBN), "L'Étranger", "Albert Camus", 3);
        // When / Then : une exception métier ultérieure annule la transaction
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            saveBookPort.save(book);
            throw new IllegalStateException("échec après sauvegarde");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(redis.hasKey(KEY)).isTrue();
        assertThat(countBooks(ISBN)).isZero();
    }

    @Test
    void register_quandLivreValide_alorsCleSupprimeeEtLectureSuivanteContientLeLivre() {
        // Given : une première lecture a mis en cache un catalogue vide
        assertThat(listAvailableBooksUseCase.listAvailable(ListAvailableBooksQuery.of(10))).isEmpty();
        assertThat(redis.opsForValue().get(KEY)).isEqualTo("[]");
        // When
        registerBookUseCase.register(new RegisterBookCommand(Isbn.of(ISBN), "L'Étranger", "Albert Camus", 3));
        // Then : RC-13, la clé a été supprimée ; RC-16, la lecture suivante voit le nouveau livre
        assertThat(redis.hasKey(KEY)).isFalse();
        List<Book> list = listAvailableBooksUseCase.listAvailable(ListAvailableBooksQuery.of(10));
        assertThat(list).singleElement().satisfies(book -> assertThat(book.getIsbn().value()).isEqualTo(ISBN));
        assertThat(redis.opsForValue().get(KEY)).contains(ISBN);
    }

    @Test
    void register_quandIsbnDejaEnregistre_alorsIsbnAlreadyExistsEtCleConservee() {
        // Given : le livre existe et la liste est en cache
        registerBookUseCase.register(new RegisterBookCommand(Isbn.of(ISBN), "L'Étranger", "Albert Camus", 3));
        listAvailableBooksUseCase.listAvailable(ListAvailableBooksQuery.of(10));
        String cached = redis.opsForValue().get(KEY);
        assertThat(cached).isNotNull();
        // When / Then : le refus (409) ne touche pas au cache
        assertThatThrownBy(() -> registerBookUseCase.register(
                new RegisterBookCommand(Isbn.of(ISBN), "Autre titre", "Autre auteur", 1)))
                .isInstanceOf(IsbnAlreadyExistsException.class);
        assertThat(redis.opsForValue().get(KEY)).isEqualTo(cached);
        assertThat(countBooks(ISBN)).isEqualTo(1);
    }

    @Test
    void list_quandDeuxLecturesApresInsertionDirecteEnBase_alorsSecondeLectureServieParLeCache() {
        // Given : trois livres, première lecture
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO book (id, isbn, title, author, total_copies, available_copies, version)"
                    + " VALUES (?, ?, ?, 'Auteur', 1, 1, 0)", UUID.randomUUID(), "978000000%04d".formatted(i),
                    "Livre %02d".formatted(i));
        }
        List<Book> first = listAvailableBooksUseCase.listAvailable(ListAvailableBooksQuery.of(10));
        // When : un livre est supprimé directement en base (sans invalidation)
        jdbc.update("DELETE FROM book WHERE isbn = ?", "9780000000000");
        List<Book> second = listAvailableBooksUseCase.listAvailable(ListAvailableBooksQuery.of(10));
        // Then : le cache sert l'ancien état (RC-16, retard jusqu'au TTL) et reste identique à la première lecture
        assertThat(first).hasSize(3);
        assertThat(second).extracting(book -> book.getId().value())
                .isEqualTo(first.stream().map(book -> book.getId().value()).toList());
        assertThat(persistenceLoad.loadAvailableBooks(10)).hasSize(2);
    }
}
