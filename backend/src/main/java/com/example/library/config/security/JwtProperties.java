package com.example.library.config.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propriétés du JWT (app.security.jwt.*). Activées par {@link SecurityConfig}.
 * Le secret (JWT_SECRET) n'en fait pas partie : il est injecté directement dans le bean de clé de {@link SecurityConfig}.
 *
 * @param ttl        durée de validité du jeton et du cookie (défaut 1 heure)
 * @param cookieName nom du cookie portant le jeton (défaut ACCESS_TOKEN)
 */
@ConfigurationProperties("app.security.jwt")
public record JwtProperties(
        @DefaultValue("PT1H") Duration ttl,
        @DefaultValue("ACCESS_TOKEN") String cookieName) {

    @Override
    public String toString() {
        return "JwtProperties[ttl=" + ttl + ", cookieName=" + cookieName + "]";
    }
}
