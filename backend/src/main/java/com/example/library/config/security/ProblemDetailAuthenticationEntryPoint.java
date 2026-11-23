package com.example.library.config.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** RM-AUTH-28 : 401 (non authentifié, jeton invalide ou expiré) en ProblemDetail, code UNAUTHENTICATED. */
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    public static final String CODE = "UNAUTHENTICATED";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        ProblemDetailResponses.write(response, HttpStatus.UNAUTHORIZED, "Authentification requise", CODE);
    }
}
