package com.example.library.catalog.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.example.library.catalog.domain.exception.InvalidBookException;

/** Livre du catalogue, avec un compteur d'exemplaires (total / disponibles). */
public final class Book {

    public static final int TEXT_MAX_LENGTH = 255;
    public static final int MIN_COPIES = 1;
    public static final int MAX_COPIES = 1000;

    private final BookId id;
    private final Isbn isbn;
    private final String title;
    private final String author;
    private final int totalCopies;
    private final int availableCopies;

    private Book(BookId id, Isbn isbn, String title, String author, int totalCopies, int availableCopies) {
        this.id = Objects.requireNonNull(id, "id");
        this.isbn = Objects.requireNonNull(isbn, "isbn");
        this.title = Objects.requireNonNull(title, "title");
        this.author = Objects.requireNonNull(author, "author");
        this.totalCopies = totalCopies;
        this.availableCopies = availableCopies;
    }

    /**
     * Enregistrement d'un nouveau livre : tous les exemplaires sont disponibles.
     * Titre et auteur : non blancs, 255 caractères maximum après trim ; exemplaires : 1 à 1000.
     * Toutes les violations sont rapportées en une seule passe.
     */
    public static Book register(Isbn isbn, String title, String author, int copies) {
        Objects.requireNonNull(isbn, "isbn");
        List<FieldViolation> violations = new ArrayList<>();
        String cleanTitle = checkText("title", title, violations);
        String cleanAuthor = checkText("author", author, violations);
        if (copies < MIN_COPIES || copies > MAX_COPIES) {
            violations.add(new FieldViolation("copies", ViolationCode.OUT_OF_RANGE));
        }
        if (!violations.isEmpty()) {
            throw new InvalidBookException(violations);
        }
        return new Book(BookId.newId(), isbn, cleanTitle, cleanAuthor, copies, copies);
    }

    /** Reconstitution depuis la persistance : aucune règle rejouée. */
    public static Book restore(BookId id, Isbn isbn, String title, String author,
                               int totalCopies, int availableCopies) {
        return new Book(id, isbn, title, author, totalCopies, availableCopies);
    }

    private static String checkText(String field, String raw, List<FieldViolation> violations) {
        if (raw == null) {
            violations.add(new FieldViolation(field, ViolationCode.REQUIRED));
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            violations.add(new FieldViolation(field, ViolationCode.BLANK));
        } else if (trimmed.length() > TEXT_MAX_LENGTH) {
            violations.add(new FieldViolation(field, ViolationCode.TOO_LONG));
        }
        return trimmed;
    }

    public BookId getId() { return id; }
    public Isbn getIsbn() { return isbn; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public int getTotalCopies() { return totalCopies; }
    public int getAvailableCopies() { return availableCopies; }
}
