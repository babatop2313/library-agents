package com.example.library.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Répond 403 aux requêtes dont l'en-tête Host ({@link LocalHostPolicy}) ou Origin ({@link LocalOriginPolicy})
 * n'est pas local.
 */
final class LocalOriginFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // en-tête brut : getServerName() normalise la valeur et masquerait certains cas
        if (!LocalHostPolicy.isAllowed(request.getHeader("Host"))) {
            // aucune valeur d'en-tête dans le journal
            logger.warn("Requête /mcp rejetée : hôte non local");
            reject(response);
            return;
        }
        if (!LocalOriginPolicy.isAllowed(request.getHeader("Origin"))) {
            logger.warn("Requête /mcp rejetée : origine non locale");
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) {
        // setStatus et non sendError : évite un renvoi vers /error, protégé par l'autre chaîne
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    }
}
