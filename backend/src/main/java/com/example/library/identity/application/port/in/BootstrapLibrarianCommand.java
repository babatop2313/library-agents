package com.example.library.identity.application.port.in;

/** Variables d'amorçage brutes (éventuellement absentes). toString masqué : jamais le mot de passe. */
public record BootstrapLibrarianCommand(String email, String password) {

    @Override
    public String toString() {
        return "BootstrapLibrarianCommand[email=***, password=***]";
    }
}
