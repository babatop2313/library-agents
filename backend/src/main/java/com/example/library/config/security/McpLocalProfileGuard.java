package com.example.library.config.security;

/**
 * Garde-fous du profil mcp-local (/mcp sans authentification) :
 * jamais avec le profil prod, et jamais sur une adresse d'écoute autre que 127.0.0.1 ou localhost.
 */
final class McpLocalProfileGuard {

    static final String PRODUCTION_PROFILE = "prod";

    private McpLocalProfileGuard() {
    }

    /**
     * Refuse le profil prod, puis toute adresse d'écoute autre que 127.0.0.1 ou localhost.
     *
     * @param serverAddress valeur effective de {@code server.address} (nulle ou vide = toutes les interfaces)
     */
    static void check(String[] activeProfiles, String serverAddress) {
        for (String profile : activeProfiles) {
            if (profile != null && PRODUCTION_PROFILE.equalsIgnoreCase(profile.trim())) {
                throw new IllegalStateException("Le profil mcp-local ne peut pas être combiné avec le profil prod");
            }
        }
        if (serverAddress == null || !isAllowedAddress(serverAddress.trim())) {
            throw new IllegalStateException("Le profil mcp-local exige server.address égal à 127.0.0.1 ou à "
                    + "localhost (valeur absente, vide ou autre refusée : elle ferait écouter d'autres interfaces)");
        }
    }

    private static boolean isAllowedAddress(String address) {
        return "127.0.0.1".equals(address) || "localhost".equalsIgnoreCase(address);
    }
}
