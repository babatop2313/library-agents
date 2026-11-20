package com.example.library.identity.domain.exception;

/** RM-AUTH-01 : un utilisateur doit avoir au moins un rôle. */
public class InvalidUserException extends DomainException {

    public static final String CODE = "EMPTY_ROLES";

    public InvalidUserException() {
        super(CODE, "Un utilisateur doit avoir au moins un rôle");
    }
}
