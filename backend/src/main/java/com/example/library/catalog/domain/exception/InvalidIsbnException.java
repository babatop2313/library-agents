package com.example.library.catalog.domain.exception;

/** ISBN qui n'est pas composé de 13 chiffres (RM-07). */
public class InvalidIsbnException extends DomainException {

    public InvalidIsbnException() {
        super("ISBN_INVALID", "L'ISBN doit comporter 13 chiffres");
    }
}
