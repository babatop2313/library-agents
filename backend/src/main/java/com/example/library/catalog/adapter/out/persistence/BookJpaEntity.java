package com.example.library.catalog.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "book")
class BookJpaEntity {

    @Id
    private UUID id;

    @Column(name = "isbn", nullable = false, length = 13)
    private String isbn;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "author", nullable = false)
    private String author;

    @Column(name = "total_copies", nullable = false)
    private int totalCopies;

    @Column(name = "available_copies", nullable = false)
    private int availableCopies;

    /** Null pour un nouvel enregistrement : JPA fait alors un persist. */
    @Version
    private Long version;

    protected BookJpaEntity() {
    }

    BookJpaEntity(UUID id, String isbn, String title, String author,
                  int totalCopies, int availableCopies, Long version) {
        this.id = id;
        this.isbn = isbn;
        this.title = title;
        this.author = author;
        this.totalCopies = totalCopies;
        this.availableCopies = availableCopies;
        this.version = version;
    }

    UUID getId() { return id; }
    String getIsbn() { return isbn; }
    String getTitle() { return title; }
    String getAuthor() { return author; }
    int getTotalCopies() { return totalCopies; }
    int getAvailableCopies() { return availableCopies; }
    Long getVersion() { return version; }
}
