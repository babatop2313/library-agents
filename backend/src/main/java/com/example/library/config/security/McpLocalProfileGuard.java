package com.example.library.config.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Garde-fous du profil mcp-local (/mcp sans authentification) :
 * jamais avec le profil prod, et jamais sur une adresse d'écoute autre que le bouclage.
 */
final class McpLocalProfileGuard {

    static final String PRODUCTION_PROFILE = "prod";

    private McpLocalProfileGuard() {
    }

    /** Refuse le profil prod. */
    static void check(String[] activeProfiles) {
        for (String profile : activeProfiles) {
            if (profile != null && PRODUCTION_PROFILE.equalsIgnoreCase(profile.trim())) {
                throw new IllegalStateException("Le profil mcp-local ne peut pas être combiné avec le profil prod");
            }
        }
    }

    /**
     * Refuse le profil prod, puis toute adresse d'écoute qui n'est pas une adresse de bouclage.
     *
     * @param serverAddress valeur effective de {@code server.address} (nulle ou vide = toutes les interfaces)
     */
    static void check(String[] activeProfiles, String serverAddress) {
        check(activeProfiles);
        if (serverAddress == null || serverAddress.isBlank()) {
            throw new IllegalStateException("Le profil mcp-local exige server.address sur une adresse de bouclage "
                    + "(127.0.0.1, ::1 ou localhost) : l'absence de valeur ferait écouter toutes les interfaces");
        }
        if (!isLoopback(serverAddress.trim())) {
            throw new IllegalStateException("Le profil mcp-local exige server.address sur une adresse de bouclage "
                    + "(127.0.0.1, ::1 ou localhost) : l'adresse configurée n'en est pas une ou n'est pas résolvable");
        }
    }

    private static boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException | SecurityException e) {
            return false;
        }
    }
}
