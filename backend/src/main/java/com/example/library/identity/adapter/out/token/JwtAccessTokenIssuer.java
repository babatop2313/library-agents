package com.example.library.identity.adapter.out.token;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.example.library.config.security.JwtClaims;
import com.example.library.config.security.JwtProperties;
import com.example.library.identity.application.port.out.AccessTokenIssuer;
import com.example.library.identity.domain.model.AccessToken;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** RM-AUTH-06 : JWT HS256 signé avec le JwtEncoder de l'application ; iss=library, sub, email, roles, iat, exp. */
@Component
class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    JwtAccessTokenIssuer(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    @Override
    public AccessToken issue(User user, Instant issuedAt) {
        // le JWT ne porte que des secondes : on tronque pour que expiresAt soit identique à la claim exp
        Instant iat = issuedAt.truncatedTo(ChronoUnit.SECONDS);
        Instant exp = iat.plus(properties.ttl());
        List<String> roles = user.roles().stream().map(Role::name).sorted().toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtClaims.ISSUER)
                .subject(user.id().value().toString())
                .claim(JwtClaims.EMAIL, user.email().value())
                .claim(JwtClaims.ROLES, roles)
                .issuedAt(iat)
                .expiresAt(exp)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, exp);
    }
}
