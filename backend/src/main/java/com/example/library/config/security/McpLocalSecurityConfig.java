package com.example.library.config.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Profil mcp-local (poste de développeur uniquement) : ouvre /mcp sans authentification.
 * La chaîne est limitée à /mcp ; toutes les autres routes restent protégées par {@link SecurityConfig}.
 */
@Configuration
@Profile("mcp-local")
public class McpLocalSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(McpLocalSecurityConfig.class);

    public McpLocalSecurityConfig(Environment environment) {
        McpLocalProfileGuard.check(environment.getActiveProfiles());
        log.warn("Profil mcp-local actif : /mcp est accessible SANS authentification et l'API n'écoute que sur "
                + "127.0.0.1. Ne jamais activer hors poste de développeur.");
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain mcpLocalSecurity(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/mcp", "/mcp/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
