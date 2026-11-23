package com.example.library.identity.domain.exception;

/** RM-AUTH-07 : même exception, même code et même message pour tous les échecs d'identification. */
public class InvalidCredentialsException extends DomainException {

    public static final String CODE = "INVALID_CREDENTIALS";
    public static final String MESSAGE = "E-mail ou mot de passe incorrect";

    public InvalidCredentialsException() {
        super(CODE, MESSAGE);
    }
}
