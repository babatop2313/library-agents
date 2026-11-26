package com.example.library.loan.adapter.in.web;

/** Le jeton est valide mais son {@code sub} n'est pas un UUID : identité inexploitable, traitée comme non authentifiée. */
class InvalidMemberIdentityException extends RuntimeException {

    InvalidMemberIdentityException(Throwable cause) {
        super("Identité du membre inexploitable", cause);
    }

    InvalidMemberIdentityException() {
        super("Identité du membre inexploitable");
    }
}
