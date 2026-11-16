package com.example.library.catalog.adapter.out.cache;

import java.time.Duration;
import java.util.List;

import com.example.library.catalog.application.port.out.LoadAvailableBooksPort;
import com.example.library.catalog.application.port.out.SaveBookPort;
import com.example.library.catalog.domain.model.AvailableBooksLimit;
import com.example.library.catalog.domain.model.Book;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Décorateur de cache (Redis) de l'adapter de persistance pour la liste des livres disponibles.
 *
 * <p>Une seule clé contient les {@value #CACHED_SIZE} premiers livres disponibles, dans l'ordre du catalogue ;
 * la lecture tronque à {@code maxResults} sans re-trier. Le cache n'est jamais la source de vérité : toute erreur
 * de cache se traduit par un repli sur la base et un WARN, sans valeur ni donnée personnelle (RC-11, RC-18).
 * La clé est supprimée après le commit d'un enregistrement (RC-13).
 */
@Component
@Primary
class AvailableBooksCacheAdapter implements LoadAvailableBooksPort, SaveBookPort {

    static final String KEY = "library:catalog:available-books:v1";
    static final int CACHED_SIZE = AvailableBooksLimit.MAX;

    private static final Logger log = LoggerFactory.getLogger(AvailableBooksCacheAdapter.class);

    /** Les trois issues de la lecture du cache (D10). */
    private sealed interface CacheRead {
        /** Valeur lue et décodée (éventuellement une liste vide). */
        record Hit(List<Book> books) implements CacheRead { }

        /** Clé absente ou valeur illisible : lecture en base puis écriture. */
        record Miss() implements CacheRead { }

        /** Redis injoignable ou commande en erreur : lecture en base, aucune écriture. */
        record Unavailable() implements CacheRead { }
    }

    private final LoadAvailableBooksPort persistentLoad;
    private final SaveBookPort persistentSave;
    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final AvailableBooksCacheCodec codec = new AvailableBooksCacheCodec();

    AvailableBooksCacheAdapter(
            @Qualifier("bookPersistenceAdapter") LoadAvailableBooksPort persistentLoad,
            @Qualifier("bookPersistenceAdapter") SaveBookPort persistentSave,
            StringRedisTemplate redis,
            @Value("${app.cache.ttl.default}") Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("app.cache.ttl.default doit être une durée strictement positive");
        }
        this.persistentLoad = persistentLoad;
        this.persistentSave = persistentSave;
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public List<Book> loadAvailableBooks(int maxResults) {
        if (maxResults < AvailableBooksLimit.MIN || maxResults > CACHED_SIZE) {
            // hors contrat du port : sémantique de la base, cache non touché
            return persistentLoad.loadAvailableBooks(maxResults);
        }
        List<Book> books = switch (readCache()) {
            case CacheRead.Hit hit -> hit.books();
            case CacheRead.Miss miss -> {
                List<Book> fromDatabase = persistentLoad.loadAvailableBooks(CACHED_SIZE);
                writeCache(fromDatabase);
                yield fromDatabase;
            }
            case CacheRead.Unavailable unavailable -> persistentLoad.loadAvailableBooks(CACHED_SIZE);
        };
        return List.copyOf(books.subList(0, Math.min(maxResults, books.size())));
    }

    @Override
    public void save(Book book) {
        persistentSave.save(book);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict();
                }
            });
        } else {
            evict();
        }
    }

    /** Ne lève jamais d'exception. */
    private CacheRead readCache() {
        String value;
        try {
            value = redis.opsForValue().get(KEY);
        } catch (RuntimeException e) {
            log.warn("Lecture du cache impossible, repli sur la base : key={}, cause={}",
                    KEY, e.getClass().getSimpleName());
            return new CacheRead.Unavailable();
        }
        if (value == null) {
            return new CacheRead.Miss();
        }
        try {
            return new CacheRead.Hit(codec.decode(value));
        } catch (UnreadableCacheValueException e) {
            String cause = e.getCause() == null ? "none" : e.getCause().getClass().getSimpleName();
            log.warn("Valeur du cache illisible, repli sur la base et remplacement : key={}, cause={}/{}",
                    KEY, e.getClass().getSimpleName(), cause);
            return new CacheRead.Miss();
        }
    }

    private void writeCache(List<Book> books) {
        try {
            redis.opsForValue().set(KEY, codec.encode(books), ttl);
        } catch (RuntimeException e) {
            log.warn("Écriture du cache impossible : key={}, cause={}", KEY, e.getClass().getSimpleName());
        }
    }

    private void evict() {
        try {
            redis.delete(KEY);
        } catch (RuntimeException e) {
            log.warn("Invalidation du cache impossible, liste potentiellement périmée jusqu'à expiration : "
                    + "key={}, cause={}", KEY, e.getClass().getSimpleName());
        }
    }
}
