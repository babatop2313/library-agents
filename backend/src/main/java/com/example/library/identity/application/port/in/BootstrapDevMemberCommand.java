package com.example.library.identity.application.port.in;

/** Variables du membre de test, brutes (éventuellement absentes). toString masqué : jamais le mot de passe. */
public record BootstrapDevMemberCommand(String email, String password) {

    @Override
    public String toString() {
        return "BootstrapDevMemberCommand[email=***, password=***]";
    }
}
