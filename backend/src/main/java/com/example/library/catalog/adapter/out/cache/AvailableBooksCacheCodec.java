package com.example.library.catalog.adapter.out.cache;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.library.catalog.domain.model.AvailableBooksLimit;
import com.example.library.catalog.domain.model.Book;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conversion {@code List<Book>} / JSON avec un {@link JsonMapper} dédié et strict (indépendant de
 * {@code spring.jackson.*}, sans typage polymorphe). Toute valeur illisible ou incohérente (D12) lève
 * {@link UnreadableCacheValueException} ; son message ne contient jamais la valeur.
 */
final class AvailableBooksCacheCodec {

    /** Motifs d'illisibilité : textes fixes, jamais construits à partir de la valeur lue ni d'un message de bibliothèque. */
    static final String REASON_UNREADABLE_JSON = "JSON illisible";
    static final String REASON_NULL_VALUE = "Valeur nulle";
    static final String REASON_TOO_MANY_ELEMENTS = "Trop d'éléments";
    static final String REASON_DUPLICATE_ISBN = "ISBN en double";
    static final String REASON_INVALID_ID_OR_ISBN = "Identifiant ou ISBN invalide";
    static final String REASON_NULL_ELEMENT = "Élément nul";
    static final String REASON_MISSING_FIELD = "Champ manquant";
    static final String REASON_BLANK_TITLE_OR_AUTHOR = "Titre ou auteur vide";
    static final String REASON_TEXT_TOO_LONG = "Titre ou auteur trop long";
    static final String REASON_INCONSISTENT_COPIES = "Nombre d'exemplaires incohérent";
    static final String REASON_AVAILABLE_ABOVE_TOTAL = "Disponibles supérieurs au total";
    static final String REASON_BOOK_NOT_AVAILABLE = "Livre non disponible";

    private static final TypeReference<List<CachedBook>> CACHED_BOOKS_TYPE = new TypeReference<>() { };

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final CachedBookMapper bookMapper = new CachedBookMapper();

    /** Tableau JSON des livres ; {@code []} si la liste est vide. */
    String encode(List<Book> books) {
        List<CachedBook> cached = books.stream().map(bookMapper::toCache).toList();
        return mapper.writeValueAsString(cached);
    }

    /** @throws UnreadableCacheValueException si la valeur est illisible ou incohérente */
    List<Book> decode(String json) {
        List<CachedBook> cached;
        try {
            cached = mapper.readValue(json, CACHED_BOOKS_TYPE);
        } catch (RuntimeException e) {
            throw new UnreadableCacheValueException(REASON_UNREADABLE_JSON, e);
        }
        if (cached == null) {
            throw new UnreadableCacheValueException(REASON_NULL_VALUE);
        }
        if (cached.size() > AvailableBooksLimit.MAX) {
            throw new UnreadableCacheValueException(REASON_TOO_MANY_ELEMENTS);
        }
        List<Book> books = new ArrayList<>(cached.size());
        Set<String> isbns = new HashSet<>();
        for (CachedBook element : cached) {
            checkConsistency(element);
            if (!isbns.add(element.isbn())) {
                throw new UnreadableCacheValueException(REASON_DUPLICATE_ISBN);
            }
            try {
                books.add(bookMapper.toDomain(element));
            } catch (RuntimeException e) {
                throw new UnreadableCacheValueException(REASON_INVALID_ID_OR_ISBN, e);
            }
        }
        return List.copyOf(books);
    }

    /**
     * Rejoue les invariants du domaine, car {@code Book.restore} n'en contrôle aucun : champs présents, titre et
     * auteur non vides et d'au plus {@link Book#TEXT_MAX_LENGTH} caractères (après {@code strip()}), total entre
     * {@link Book#MIN_COPIES} et {@link Book#MAX_COPIES}, disponibles entre 1 et le total.
     */
    private static void checkConsistency(CachedBook element) {
        if (element == null) {
            throw new UnreadableCacheValueException(REASON_NULL_ELEMENT);
        }
        if (element.id() == null || element.isbn() == null || element.title() == null || element.author() == null) {
            throw new UnreadableCacheValueException(REASON_MISSING_FIELD);
        }
        if (element.title().isBlank() || element.author().isBlank()) {
            throw new UnreadableCacheValueException(REASON_BLANK_TITLE_OR_AUTHOR);
        }
        if (element.title().strip().length() > Book.TEXT_MAX_LENGTH
                || element.author().strip().length() > Book.TEXT_MAX_LENGTH) {
            throw new UnreadableCacheValueException(REASON_TEXT_TOO_LONG);
        }
        if (element.totalCopies() < Book.MIN_COPIES || element.totalCopies() > Book.MAX_COPIES
                || element.availableCopies() < 0) {
            throw new UnreadableCacheValueException(REASON_INCONSISTENT_COPIES);
        }
        if (element.availableCopies() > element.totalCopies()) {
            throw new UnreadableCacheValueException(REASON_AVAILABLE_ABOVE_TOTAL);
        }
        if (element.availableCopies() < 1) {
            throw new UnreadableCacheValueException(REASON_BOOK_NOT_AVAILABLE);
        }
    }
}
