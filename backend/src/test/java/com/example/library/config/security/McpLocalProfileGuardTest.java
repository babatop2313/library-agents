package com.example.library.config.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Decision 4 : mcp-local ne cohabite jamais avec prod. */
class McpLocalProfileGuardTest {

    @Test
    void check_quandMcpLocalEtProd_alorsRefus() {
        // When / Then
        assertThatThrownBy(() -> McpLocalProfileGuard.check(new String[] {"mcp-local", "prod"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Le profil mcp-local ne peut pas être combiné avec le profil prod");
    }

    @Test
    void check_quandProdEnMajusculesAvantMcpLocal_alorsRefus() {
        // When / Then
        assertThatThrownBy(() -> McpLocalProfileGuard.check(new String[] {"PROD", "mcp-local"}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void check_quandProdAvecEspaces_alorsRefus() {
        // When / Then
        assertThatThrownBy(() -> McpLocalProfileGuard.check(new String[] {"mcp-local", " Prod "}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void check_quandMcpLocalSeul_alorsAccepte() {
        // When / Then
        assertThatCode(() -> McpLocalProfileGuard.check(new String[] {"mcp-local"})).doesNotThrowAnyException();
    }

    @Test
    void check_quandMcpLocalEtLocal_alorsAccepte() {
        // When / Then
        assertThatCode(() -> McpLocalProfileGuard.check(new String[] {"mcp-local", "local"}))
                .doesNotThrowAnyException();
    }

    @Test
    void check_quandProfilNomVoisinDeProd_alorsAccepte() {
        // When / Then : seul le nom exact "prod" est interdit
        assertThatCode(() -> McpLocalProfileGuard.check(new String[] {"mcp-local", "production", "preprod"}))
                .doesNotThrowAnyException();
    }
}
