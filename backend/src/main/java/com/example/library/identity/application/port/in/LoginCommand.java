package com.example.library.identity.application.port.in;

/** Identifiants saisis, non normalisés. toString masqué : le mot de passe ne doit jamais apparaître. */
public record LoginCommand(String email, String password) {

    @Override
    public String toString() {
        return "LoginCommand[email=***, password=***]";
    }
}
