package com.example.library.loan.domain.exception;

/**
 * Exception métier du contexte emprunts, identifiée par un code métier stable.
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
