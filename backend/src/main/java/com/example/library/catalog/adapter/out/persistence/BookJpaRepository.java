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

    /** COLLATE "C" : même ordre que toLowerCase(Locale.ROOT) puis ISBN côté Java, quelle que soit la collation de la base. */
    @Query(value = """
            SELECT * FROM book
            WHERE available_copies >= 1
            ORDER BY lower(title) COLLATE "C", isbn COLLATE "C"
            LIMIT :max
            """, nativeQuery = true)
    List<BookJpaEntity> findAvailableOrderedByTitle(@Param("max") int max);
}
