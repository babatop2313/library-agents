package com.example.library.config.security;

/** Noms des claims propres à l'application, partagés entre l'émission (identity) et la lecture (sécurité). */
public final class JwtClaims {

    public static final String ROLES = "roles";
    public static final String EMAIL = "email";
    public static final String ISSUER = "library";

    private JwtClaims() {
    }
}
