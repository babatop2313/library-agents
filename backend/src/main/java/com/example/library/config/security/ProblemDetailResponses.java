package com.example.library.config.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Écrit un ProblemDetail (RFC 9457) pour les erreurs produites par la couche de sécurité, avant tout contrôleur.
 * Les valeurs sont des constantes de l'application (aucune donnée de la requête) : pas d'échappement nécessaire.
 */
final class ProblemDetailResponses {

    private ProblemDetailResponses() {
    }

    static void write(HttpServletResponse response, HttpStatus status, String title, String code) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status.value()
                + ",\"code\":\"" + code + "\"}";
        response.getWriter().write(body);
    }
}
