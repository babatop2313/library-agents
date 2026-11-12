package com.example.library.config.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;

import com.example.library.TestcontainersConfiguration;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/** RM-MCP-10 : sans le profil mcp-local, /mcp exige un JWT valide (decisions 3 et 4). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class McpSecurityTest {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
            "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    private static ResultMatcher notUnauthorized() {
        return result -> {
            int status = result.getResponse().getStatus();
            if (status == 401 || status == 403) {
                throw new AssertionError("Statut inattendu : " + status);
            }
        };
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder mcpPost() {
        return post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(INITIALIZE);
    }

    private static String token(JwtEncoder encoder, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("member-1")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("roles", List.of("MEMBER"))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    @Test
    void mcp_quandSansJwt_alorsNonAuthentifie401() throws Exception {
        // When / Then
        mockMvc.perform(mcpPost()).andExpect(status().isUnauthorized());
    }

    @Test
    void mcp_quandJwtIllisible_alorsNonAuthentifie401() throws Exception {
        // When / Then
        mockMvc.perform(mcpPost().header("Authorization", "Bearer pas.un.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mcp_quandJwtMalSigne_alorsNonAuthentifie401() throws Exception {
        // Given : un JWT valide en forme mais signe avec une autre cle
        byte[] otherKey = "une_autre_cle_de_signature_de_32_octets_minimum!".getBytes(StandardCharsets.UTF_8);
        JwtEncoder foreign = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
        Instant now = Instant.now();
        String forged = token(foreign, now, now.plus(1, ChronoUnit.HOURS));
        // When / Then
        mockMvc.perform(mcpPost().header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mcp_quandJwtExpire_alorsNonAuthentifie401() throws Exception {
        // Given : JWT signe avec la bonne cle mais expire depuis une heure
        Instant now = Instant.now();
        String expired = token(jwtEncoder, now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS));
        // When / Then
        mockMvc.perform(mcpPost().header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mcp_quandJwtValideRoleMember_alorsPasRejeteParLaSecurite() throws Exception {
        // When / Then
        mockMvc.perform(mcpPost().with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(notUnauthorized());
    }

    @Test
    void mcp_quandJwtValideRoleLibrarian_alorsPasRejeteParLaSecurite() throws Exception {
        // When / Then
        mockMvc.perform(mcpPost().with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LIBRARIAN"))))
                .andExpect(notUnauthorized());
    }

    @Test
    void mcp_quandJwtReelSigneAvecLaBonneCle_alorsPasRejeteParLaSecurite() throws Exception {
        // Given
        Instant now = Instant.now();
        String valid = token(jwtEncoder, now, now.plus(1, ChronoUnit.HOURS));
        // When / Then
        mockMvc.perform(mcpPost().header("Authorization", "Bearer " + valid)).andExpect(notUnauthorized());
    }

    @Test
    void mcpSousChemin_quandSansJwt_alorsNonAuthentifie401() throws Exception {
        // When / Then
        mockMvc.perform(post("/mcp/sub").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
