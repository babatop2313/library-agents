package com.example.library.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Membre de test du profil local (DEV_MEMBER_EMAIL / DEV_MEMBER_PASSWORD) ; une valeur vide vaut absence.
 * toString masqué : le mot de passe ne doit jamais apparaître.
 */
@ConfigurationProperties("app.dev.member")
public record DevMemberProperties(String email, String password) {

    @Override
    public String toString() {
        return "DevMemberProperties[email=***, password=***]";
    }
}
