package com.example.library.catalog.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BookJpaRepository extends JpaRepository<BookJpaEntity, UUID> {

    boolean existsByIsbn(String isbn);

    Optional<BookJpaEntity> findByIsbn(String isbn);

    /**
     * COLLATE "C" : même ordre que toLowerCase(Locale.ROOT) puis ISBN côté Java, quelle que soit la collation
     * de la base. Partagé par toutes les requêtes : garantit l'identité entre la tête de liste du cache et la base.
     */
    String CATALOG_ORDER_BY = "ORDER BY lower(title) COLLATE \"C\", isbn COLLATE \"C\"";

    @Query(value = "SELECT * FROM book WHERE available_copies >= 1 " + CATALOG_ORDER_BY + " LIMIT :max",
            nativeQuery = true)
    List<BookJpaEntity> findAvailableOrderedByTitle(@Param("max") int max);

    @Query(value = "SELECT * FROM book WHERE available_copies >= 1 " + CATALOG_ORDER_BY
            + " LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<BookJpaEntity> findAvailablePage(@Param("limit") int limit, @Param("offset") long offset);

    /** {@code pattern} : motif LIKE déjà échappé (voir LikePatterns), ESCAPE '\'. */
    @Query(value = "SELECT * FROM book WHERE available_copies >= 1"
            + " AND (lower(title) LIKE lower(:pattern) ESCAPE '\\' OR lower(author) LIKE lower(:pattern) ESCAPE '\\') "
            + CATALOG_ORDER_BY + " LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<BookJpaEntity> searchAvailablePage(@Param("pattern") String pattern, @Param("limit") int limit,
                                            @Param("offset") long offset);

    @Query(value = "SELECT count(*) FROM book WHERE available_copies >= 1", nativeQuery = true)
    long countAvailable();

    @Query(value = "SELECT count(*) FROM book WHERE available_copies >= 1"
            + " AND (lower(title) LIKE lower(:pattern) ESCAPE '\\' OR lower(author) LIKE lower(:pattern) ESCAPE '\\')",
            nativeQuery = true)
    long countAvailableMatching(@Param("pattern") String pattern);
}
