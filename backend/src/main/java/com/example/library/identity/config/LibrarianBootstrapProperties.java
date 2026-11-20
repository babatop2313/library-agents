package com.example.library.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Variables d'amorçage du premier bibliothécaire (BOOTSTRAP_LIBRARIAN_EMAIL / _PASSWORD), sans valeur par défaut.
 * toString masqué : le mot de passe ne doit jamais apparaître.
 */
@ConfigurationProperties("app.bootstrap.librarian")
public record LibrarianBootstrapProperties(String email, String password) {

    @Override
    public String toString() {
        return "LibrarianBootstrapProperties[email=***, password=***]";
    }
}
