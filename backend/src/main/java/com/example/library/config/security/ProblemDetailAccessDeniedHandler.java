package com.example.library.config.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/** RM-AUTH-28 : 403 (rôle insuffisant ou jeton CSRF manquant) en ProblemDetail, code ACCESS_DENIED. */
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    public static final String CODE = "ACCESS_DENIED";

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ProblemDetailResponses.write(response, HttpStatus.FORBIDDEN, "Accès refusé", CODE);
    }
}
