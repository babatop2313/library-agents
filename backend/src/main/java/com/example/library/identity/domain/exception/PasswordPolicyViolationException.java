package com.example.library.identity.domain.exception;

/** RM-AUTH-03 : mot de passe hors politique. Ni la valeur ni sa longueur ne figurent dans le message. */
public class PasswordPolicyViolationException extends DomainException {

    public static final String CODE = "WEAK_PASSWORD";

    public enum Reason { TOO_SHORT, TOO_LONG }

    private final Reason reason;

    public PasswordPolicyViolationException(Reason reason) {
        super(CODE, "Le mot de passe ne respecte pas la politique (" + reason + ")");
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
