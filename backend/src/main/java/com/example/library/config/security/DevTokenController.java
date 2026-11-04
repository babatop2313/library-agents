package com.example.library.config.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Émet des JWT de test pour appeler l'API en local (Postman, curl).
 * Actif UNIQUEMENT avec le profil "local" : n'existe pas dans les autres environnements.
 *
 * Exemple :
 *   curl -X POST localhost:8080/dev/token -H 'Content-Type: application/json' \
 *        -d '{"roles":["LIBRARIAN"]}'
 */
@RestController
@Profile("local")
class DevTokenController {

    private final JwtEncoder jwtEncoder;

    DevTokenController(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    record TokenRequest(String subject, List<String> roles) {}

    record TokenResponse(String accessToken, String subject, Instant expiresAt) {}

    @PostMapping("/dev/token")
    TokenResponse issue(@RequestBody TokenRequest request) {
        String subject = request.subject() != null ? request.subject() : UUID.randomUUID().toString();
        List<String> roles = request.roles() != null ? request.roles() : List.of("MEMBER");
        Instant now = Instant.now();
        Instant expiresAt = now.plus(8, ChronoUnit.HOURS);

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("library-dev")
            .subject(subject)
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(SecurityConfig.ROLES_CLAIM, roles)
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, subject, expiresAt);
    }
}
