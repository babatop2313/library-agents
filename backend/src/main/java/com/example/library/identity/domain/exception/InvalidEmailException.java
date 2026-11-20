package com.example.library.identity.domain.exception;

/** E-mail absent ou mal formé. Le message ne reprend jamais la valeur saisie. */
public class InvalidEmailException extends DomainException {

    public static final String CODE = "INVALID_EMAIL";

    public InvalidEmailException() {
        super(CODE, "L'adresse e-mail est invalide");
    }
}
