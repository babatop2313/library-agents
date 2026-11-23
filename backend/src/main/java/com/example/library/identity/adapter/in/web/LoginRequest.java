package com.example.library.identity.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Corps de POST /api/v1/auth/login. Aucune règle de longueur minimale sur le mot de passe (RM-AUTH-03).
 * toString masqué : ni l'e-mail ni le mot de passe ne doivent apparaître dans un journal.
 */
record LoginRequest(
        @NotNull @NotBlank @MaxTrimmedLength(254) @ValidEmailFormat String email,
        @NotNull @NotEmpty String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=***, password=***]";
    }
}
