package com.example.library.identity.adapter.out.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.example.library.config.security.JwtClaims;
import com.example.library.config.security.JwtProperties;
import com.example.library.identity.domain.model.AccessToken;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.PasswordHash;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** RM-AUTH-05 et RM-AUTH-06 : forme du JWT émis (HS256, claims, durée d'une heure). */
class JwtAccessTokenIssuerTest {

    private static final SecretKey KEY = new SecretKeySpec(
            "test-only-secret-key-not-for-production-0123456789".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private static final Instant ISSUED_AT = Instant.parse("2026-11-20T10:00:00.789Z");

    private final JwtProperties properties = new JwtProperties("ignored", Duration.ofHours(1), "ACCESS_TOKEN");
    private final JwtAccessTokenIssuer issuer =
            new JwtAccessTokenIssuer(new NimbusJwtEncoder(new ImmutableSecret<>(KEY)), properties);

    private static User user(Role... roles) {
        return User.register(Email.of("alice@example.org"), new PasswordHash("$2a$10$secrethash"), Set.of(roles),
                ISSUED_AT);
    }

    private Jwt decode(AccessToken token) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();
        // dates figées dans le passé : seule la signature HS256 est vérifiée ici
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        return decoder.decode(token.value());
    }

    @Test
    void issue_quandUtilisateurMembre_alorsClaimsConformesALaSpec() {
        // Given
        User user = user(Role.MEMBER);

        // When
        AccessToken token = issuer.issue(user, ISSUED_AT);
        Jwt jwt = decode(token);

        // Then
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("library");
        assertThat(jwt.getSubject()).isEqualTo(user.id().value().toString());
        assertThat(jwt.getClaimAsString(JwtClaims.EMAIL)).isEqualTo("alice@example.org");
        assertThat(jwt.getClaimAsStringList(JwtClaims.ROLES)).containsExactly("MEMBER");
    }

    @Test
    void issue_quandTtlUneHeure_alorsExpEgaleIatPlusUneHeureEtExpiresAtCoherent() {
        // When
        AccessToken token = issuer.issue(user(Role.MEMBER), ISSUED_AT);
        Jwt jwt = decode(token);

        // Then : le JWT ne porte que des secondes
        assertThat(jwt.getIssuedAt()).isEqualTo(Instant.parse("2026-11-20T10:00:00Z"));
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plus(Duration.ofHours(1)));
        assertThat(token.expiresAt()).isEqualTo(jwt.getExpiresAt());
    }

    @Test
    void issue_quandRolesCumules_alorsLesDeuxRolesDansLeJwt() {
        // When
        Jwt jwt = decode(issuer.issue(user(Role.MEMBER, Role.LIBRARIAN), ISSUED_AT));

        // Then
        assertThat(jwt.getClaimAsStringList(JwtClaims.ROLES)).containsExactlyInAnyOrder("MEMBER", "LIBRARIAN");
    }

    @Test
    void issue_quandJeton_alorsNiMotDePasseNiHashDansLesClaims() {
        // When
        Jwt jwt = decode(issuer.issue(user(Role.MEMBER), ISSUED_AT));

        // Then
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "email", "roles", "iat", "exp");
        assertThat(jwt.getClaims().toString()).doesNotContain("secrethash").doesNotContain("$2a");
    }

    @Test
    void issue_quandTtlConfigureDifferent_alorsExpSuitLaConfiguration() {
        // Given
        JwtAccessTokenIssuer shortLived = new JwtAccessTokenIssuer(new NimbusJwtEncoder(new ImmutableSecret<>(KEY)),
                new JwtProperties("ignored", Duration.ofMinutes(5), "ACCESS_TOKEN"));

        // When
        Jwt jwt = decode(shortLived.issue(user(Role.MEMBER), ISSUED_AT));

        // Then
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void roles_quandListeDeClaims_alorsTypeListeDeChaines() {
        // When
        Jwt jwt = decode(issuer.issue(user(Role.LIBRARIAN), ISSUED_AT));

        // Then
        Object roles = jwt.getClaim(JwtClaims.ROLES);
        assertThat(roles).isInstanceOf(List.class);
    }
}
