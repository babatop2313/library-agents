package com.example.library.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** RM-AUTH-28 : 401 et 403 de la couche de sécurité en ProblemDetail avec un code stable. */
class ProblemDetailSecurityHandlersTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void commence_quandNonAuthentifie_alorsProblemDetail401Unauthenticated() throws Exception {
        // Given
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        new ProblemDetailAuthenticationEntryPoint()
                .commence(new MockHttpServletRequest(), response, new BadCredentialsException("jeton invalide"));

        // Then
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.get("title").asString()).isEqualTo("Authentification requise");
    }

    @Test
    void commence_quandMessageDeLException_alorsAucuneFuiteDansLeCorps() throws Exception {
        // Given
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        new ProblemDetailAuthenticationEntryPoint()
                .commence(new MockHttpServletRequest(), response, new BadCredentialsException("detail-interne"));

        // Then
        assertThat(response.getContentAsString()).doesNotContain("detail-interne");
    }

    @Test
    void handle_quandAccesRefuse_alorsProblemDetail403AccessDenied() throws Exception {
        // Given
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        new ProblemDetailAccessDeniedHandler()
                .handle(new MockHttpServletRequest(), response, new AccessDeniedException("interne"));

        // Then
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("code").asString()).isEqualTo("ACCESS_DENIED");
        assertThat(body.get("title").asString()).isEqualTo("Accès refusé");
        assertThat(response.getContentAsString()).doesNotContain("interne");
    }
}
