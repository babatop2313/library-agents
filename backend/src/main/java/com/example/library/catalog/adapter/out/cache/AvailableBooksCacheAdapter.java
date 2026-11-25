package com.example.library.catalog.adapter.out.cache;

import java.time.Duration;
import java.util.List;

import com.example.library.catalog.application.port.out.ChangeAvailableCopiesPort;
import com.example.library.catalog.application.port.out.LoadAvailableBooksPagePort;
import com.example.library.catalog.application.port.out.LoadAvailableBooksPort;
import com.example.library.catalog.application.port.out.SaveBookPort;
import com.example.library.catalog.domain.model.AvailableBooksLimit;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.CatalogPageRequest;
import com.example.library.catalog.domain.model.SearchText;
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
 *
 * <p>RM-CAT-42 : ce décorateur implémente tout port d'écriture annoté {@code @AffectsAvailableBooks}
 * (aujourd'hui {@link SaveBookPort} et {@link ChangeAvailableCopiesPort}, cette dernière appelée à chaque emprunt et
 * retour) et appelle {@code evictAfterCommit()} après avoir délégué, seulement si une ligne a été modifiée ; les
 * autres modules (ex. {@code loan}) passent par {@code catalog.api}, jamais par le cache. Les pages sans recherche
 * qui tiennent dans la tête de liste sont servies par la même clé ; le total n'est jamais mis en cache.
 *
 * <p>Dette technique (décision D6 du module loan) : ce décorateur compte désormais 6 dépendances, au-delà du seuil
 * de 5 du socle. À refondre (issue : « Refondre le décorateur de cache du catalogue (dépendances &gt; 5) »),
 * par exemple en regroupant les ports de lecture ou d'écriture, pour revenir à 5 dépendances au plus.
 */
@Component
@Primary
class AvailableBooksCacheAdapter
        implements LoadAvailableBooksPort, LoadAvailableBooksPagePort, SaveBookPort, ChangeAvailableCopiesPort {

    static final String KEY = "library:catalog:available-books:v1";
    static final int CACHED_SIZE = AvailableBooksLimit.MAX;

    /** Gabarits des WARN (placeholders SLF4J) : jamais de valeur du cache ni de message de bibliothèque. */
    static final String LOG_READ_FAILED = "Lecture du cache impossible, repli sur la base : key={}, cause={}";
    static final String LOG_UNREADABLE_VALUE =
            "Valeur du cache illisible, repli sur la base et remplacement : key={}, cause={}/{}, reason={}";
    static final String LOG_WRITE_FAILED = "Écriture du cache impossible : key={}, cause={}";
    static final String LOG_EVICT_FAILED =
            "Invalidation du cache impossible, liste potentiellement périmée jusqu'à expiration : key={}, cause={}";

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
    private final LoadAvailableBooksPagePort persistentPageLoad;
    private final SaveBookPort persistentSave;
    private final ChangeAvailableCopiesPort persistentChange;
    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final AvailableBooksCacheCodec codec = new AvailableBooksCacheCodec();

    AvailableBooksCacheAdapter(
            @Qualifier("bookPersistenceAdapter") LoadAvailableBooksPort persistentLoad,
            @Qualifier("bookPersistenceAdapter") LoadAvailableBooksPagePort persistentPageLoad,
            @Qualifier("bookPersistenceAdapter") SaveBookPort persistentSave,
            @Qualifier("bookPersistenceAdapter") ChangeAvailableCopiesPort persistentChange,
            StringRedisTemplate redis,
            @Value("${app.cache.ttl.default}") Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("app.cache.ttl.default doit être une durée strictement positive");
        }
        this.persistentLoad = persistentLoad;
        this.persistentPageLoad = persistentPageLoad;
        this.persistentSave = persistentSave;
        this.persistentChange = persistentChange;
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public List<Book> loadAvailableBooks(int maxResults) {
        if (maxResults < AvailableBooksLimit.MIN || maxResults > CACHED_SIZE) {
            // hors contrat du port : sémantique de la base, cache non touché
            return persistentLoad.loadAvailableBooks(maxResults);
        }
        List<Book> books = cachedHead();
        return List.copyOf(books.subList(0, Math.min(maxResults, books.size())));
    }

    /**
     * Page du catalogue : la tête de liste (clé {@code v1}) sert uniquement les pages sans recherche qui tiennent
     * dans les {@value #CACHED_SIZE} premiers livres ; sinon base de données, cache ni lu ni écrit (RM-CAT-24).
     */
    @Override
    public List<Book> loadAvailableBooksPage(SearchText text, CatalogPageRequest pageRequest) {
        if (!text.isEmpty() || pageRequest.endExclusive() > CACHED_SIZE) {
            return persistentPageLoad.loadAvailableBooksPage(text, pageRequest);
        }
        List<Book> books = cachedHead();
        int from = (int) Math.min(pageRequest.offset(), books.size());
        int to = (int) Math.min(pageRequest.endExclusive(), books.size());
        return List.copyOf(books.subList(from, to));
    }

    /** Les {@value #CACHED_SIZE} premiers livres disponibles : cache (Hit), ou base (Miss avec écriture, Unavailable sans). */
    private List<Book> cachedHead() {
        return switch (readCache()) {
            case CacheRead.Hit hit -> hit.books();
            case CacheRead.Miss miss -> {
                List<Book> fromDatabase = persistentLoad.loadAvailableBooks(CACHED_SIZE);
                writeCache(fromDatabase);
                yield fromDatabase;
            }
            case CacheRead.Unavailable unavailable -> persistentLoad.loadAvailableBooks(CACHED_SIZE);
        };
    }

    @Override
    public void save(Book book) {
        persistentSave.save(book);
        evictAfterCommit();
    }

    @Override
    public boolean decrementAvailableCopies(BookId id) {
        boolean changed = persistentChange.decrementAvailableCopies(id);
        if (changed) {
            evictAfterCommit();
        }
        return changed;
    }

    @Override
    public boolean incrementAvailableCopies(BookId id) {
        boolean changed = persistentChange.incrementAvailableCopies(id);
        if (changed) {
            evictAfterCommit();
        }
        return changed;
    }

    /**
     * Point d'extension RM-CAT-42 : toute écriture qui modifie les livres disponibles supprime la clé après le commit
     * (immédiatement hors transaction ; rien en cas de rollback ; une erreur Redis n'est jamais propagée).
     */
    private void evictAfterCommit() {
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
            log.warn(LOG_READ_FAILED, KEY, e.getClass().getSimpleName());
            return new CacheRead.Unavailable();
        }
        if (value == null) {
            return new CacheRead.Miss();
        }
        try {
            return new CacheRead.Hit(codec.decode(value));
        } catch (UnreadableCacheValueException e) {
            String cause = e.getCause() == null ? "none" : e.getCause().getClass().getSimpleName();
            // reason : texte fixe du codec ; jamais la valeur lue ni le message de la cause (RC-18)
            log.warn(LOG_UNREADABLE_VALUE, KEY, e.getClass().getSimpleName(), cause, e.getMessage());
            return new CacheRead.Miss();
        }
    }

    private void writeCache(List<Book> books) {
        try {
            redis.opsForValue().set(KEY, codec.encode(books), ttl);
        } catch (RuntimeException e) {
            log.warn(LOG_WRITE_FAILED, KEY, e.getClass().getSimpleName());
        }
    }

    private void evict() {
        try {
            redis.delete(KEY);
        } catch (RuntimeException e) {
            log.warn(LOG_EVICT_FAILED, KEY, e.getClass().getSimpleName());
        }
    }
}
