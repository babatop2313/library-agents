package com.example.library.catalog.adapter.out.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface BookJpaRepository extends JpaRepository<BookJpaEntity, UUID> {

    boolean existsByIsbn(String isbn);
}
