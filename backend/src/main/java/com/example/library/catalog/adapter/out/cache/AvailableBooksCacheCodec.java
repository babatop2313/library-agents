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
            throw new UnreadableCacheValueException("JSON illisible", e);
        }
        if (cached == null) {
            throw new UnreadableCacheValueException("Valeur nulle");
        }
        if (cached.size() > AvailableBooksLimit.MAX) {
            throw new UnreadableCacheValueException("Trop d'éléments");
        }
        List<Book> books = new ArrayList<>(cached.size());
        Set<String> isbns = new HashSet<>();
        for (CachedBook element : cached) {
            checkConsistency(element);
            if (!isbns.add(element.isbn())) {
                throw new UnreadableCacheValueException("ISBN en double");
            }
            try {
                books.add(bookMapper.toDomain(element));
            } catch (RuntimeException e) {
                throw new UnreadableCacheValueException("Identifiant ou ISBN invalide", e);
            }
        }
        return List.copyOf(books);
    }

    private static void checkConsistency(CachedBook element) {
        if (element == null) {
            throw new UnreadableCacheValueException("Élément nul");
        }
        if (element.id() == null || element.isbn() == null || element.title() == null || element.author() == null) {
            throw new UnreadableCacheValueException("Champ manquant");
        }
        if (element.title().isBlank() || element.author().isBlank()) {
            throw new UnreadableCacheValueException("Titre ou auteur vide");
        }
        if (element.totalCopies() < Book.MIN_COPIES || element.availableCopies() < 0) {
            throw new UnreadableCacheValueException("Nombre d'exemplaires incohérent");
        }
        if (element.availableCopies() > element.totalCopies()) {
            throw new UnreadableCacheValueException("Plus d'exemplaires disponibles que d'exemplaires au total");
        }
        if (element.availableCopies() < 1) {
            throw new UnreadableCacheValueException("Livre non disponible");
        }
    }
}
