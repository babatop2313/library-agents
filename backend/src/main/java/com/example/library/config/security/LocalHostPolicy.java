package com.example.library.config.security;

import java.util.regex.Pattern;

/**
 * Politique d'en-tête Host du profil mcp-local (défense contre le DNS rebinding).
 * Correspondance stricte : exactement localhost ou 127.0.0.1, port numérique optionnel ;
 * ni majuscules, ni IPv6, ni suffixe, ni « : » sans port.
 */
final class LocalHostPolicy {

    private static final Pattern LOCAL_HOST = Pattern.compile("(?:localhost|127\\.0\\.0\\.1)(?::[0-9]{1,5})?");

    private LocalHostPolicy() {
    }

    /** En-tête absent ou vide : refusé. */
    static boolean isAllowed(String hostHeader) {
        if (hostHeader == null || hostHeader.isEmpty()) {
            return false;
        }
        return LOCAL_HOST.matcher(hostHeader).matches();
    }
}
