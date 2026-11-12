package com.example.library.config.security;

/** Garde-fou : le profil mcp-local (/mcp sans authentification) ne doit jamais cohabiter avec le profil prod. */
final class McpLocalProfileGuard {

    static final String PRODUCTION_PROFILE = "prod";

    private McpLocalProfileGuard() {
    }

    static void check(String[] activeProfiles) {
        for (String profile : activeProfiles) {
            if (profile != null && PRODUCTION_PROFILE.equalsIgnoreCase(profile.trim())) {
                throw new IllegalStateException("Le profil mcp-local ne peut pas être combiné avec le profil prod");
            }
        }
    }
}
