package com.example.library.config.security;

import java.util.regex.Pattern;

/**
 * Politique d'origine du profil mcp-local : protège /mcp (sans authentification) contre les pages web
 * tierces ouvertes dans le navigateur du développeur (DNS rebinding, requêtes cross-origin).
 * Parsing strict : schéma http, hôte exactement localhost ou 127.0.0.1, port optionnel numérique, aucun chemin.
 */
final class LocalOriginPolicy {

    private static final Pattern LOCAL_ORIGIN =
            Pattern.compile("http://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]{1,5})?");

    private LocalOriginPolicy() {
    }

    /** Sans en-tête Origin (valeur nulle) la requête passe ; sinon l'origine doit être locale. */
    static boolean isAllowed(String originHeader) {
        if (originHeader == null) {
            return true;
        }
        return LOCAL_ORIGIN.matcher(originHeader).matches();
    }
}
