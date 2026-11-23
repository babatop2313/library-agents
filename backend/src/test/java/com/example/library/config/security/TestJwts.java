package com.example.library.config.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Aide de test : forge des JWT HS256 avec la clé de test (celle de {@code src/test/resources/application.properties}),
 * jamais via /dev/token (décision 14). Utilise la même claim {@code roles} que la production.
 */
public final class TestJwts {

    /** Même valeur que JWT_SECRET dans src/test/resources/application.properties. */
    public static final String TEST_SECRET = "test-only-secret-key-not-for-production-0123456789";
    public static final String OTHER_SECRET = "another-secret-key-for-forged-tokens-0123456789";

    private static final JwtEncoder ENCODER = encoder(TEST_SECRET);
    private static final JwtEncoder OTHER_ENCODER = encoder(OTHER_SECRET);

    private TestJwts() {
    }

    private static JwtEncoder encoder(String secret) {
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    public static String member() {
        return forge(ENCODER, UUID.randomUUID().toString(), "member@example.org", List.of("MEMBER"), validity());
    }

    public static String librarian() {
        return forge(ENCODER, UUID.randomUUID().toString(), "librarian@example.org", List.of("LIBRARIAN"), validity());
    }

    public static String withRoles(String subject, String email, String... roles) {
        return forge(ENCODER, subject, email, List.of(roles), validity());
    }

    /** Jeton sans claim e-mail, comme celui de /dev/token (profil local). */
    public static String withoutEmail(String subject, String... roles) {
        return forge(ENCODER, subject, null, List.of(roles), validity());
    }

    public static String expired() {
        Instant issuedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        return forge(ENCODER, UUID.randomUUID().toString(), "member@example.org", List.of("MEMBER"),
                new Instant[] {issuedAt, issuedAt.plus(1, ChronoUnit.HOURS)});
    }

    public static String signedWithOtherKey() {
        return forge(OTHER_ENCODER, UUID.randomUUID().toString(), "member@example.org", List.of("LIBRARIAN"),
                validity());
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    private static Instant[] validity() {
        Instant now = Instant.now();
        return new Instant[] {now, now.plus(1, ChronoUnit.HOURS)};
    }

    private static String forge(JwtEncoder encoder, String subject, String email, List<String> roles,
                                Instant[] validity) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(JwtClaims.ISSUER)
                .subject(subject)
                .issuedAt(validity[0])
                .expiresAt(validity[1])
                .claim(JwtClaims.ROLES, roles);
        if (email != null) {
            claims.claim(JwtClaims.EMAIL, email);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
