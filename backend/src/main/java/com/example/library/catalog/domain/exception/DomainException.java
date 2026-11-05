package com.example.library.catalog.domain.exception;

/**
 * Exception métier du contexte catalogue, identifiée par un code métier.
 * Aucune notion de HTTP : la traduction en réponse est faite par l'adapter web.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
