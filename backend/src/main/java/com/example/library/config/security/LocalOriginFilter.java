package com.example.library.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Répond 403 aux requêtes dont l'en-tête Origin n'est pas local ({@link LocalOriginPolicy}). */
final class LocalOriginFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (LocalOriginPolicy.isAllowed(request.getHeader("Origin"))) {
            chain.doFilter(request, response);
        } else {
            // aucune valeur d'en-tête dans le journal
            logger.warn("Requête /mcp rejetée : origine non locale");
            // setStatus et non sendError : évite un renvoi vers /error, protégé par l'autre chaîne
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        }
    }
}
