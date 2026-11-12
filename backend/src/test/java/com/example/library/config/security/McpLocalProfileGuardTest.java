package com.example.library.config.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

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

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "localhost"})
    void check_quandAdresseDeBouclage_alorsAccepte(String address) {
        // When / Then
        assertThatCode(() -> McpLocalProfileGuard.check(new String[] {"mcp-local"}, address))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "0.0.0.0", "192.168.1.10", "hote-inexistant.invalid"})
    void check_quandAdresseAbsenteOuNonBouclage_alorsRefus(String address) {
        // When / Then
        assertThatThrownBy(() -> McpLocalProfileGuard.check(new String[] {"mcp-local"}, address))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.address");
    }

    @Test
    void check_quandProdAvecAdresseValide_alorsRefusProd() {
        // When / Then
        assertThatThrownBy(() -> McpLocalProfileGuard.check(new String[] {"mcp-local", "prod"}, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Le profil mcp-local ne peut pas être combiné avec le profil prod");
    }

    @Test
    void constructeur_quandServerAddressEcraseeAToutesInterfaces_alorsRefus() {
        // Given : un écrasement de server.address (variable d'environnement, ligne de commande) vu par l'Environment
        MockEnvironment environment = new MockEnvironment().withProperty("server.address", "0.0.0.0");
        environment.setActiveProfiles("mcp-local");
        // When / Then
        assertThatThrownBy(() -> new McpLocalSecurityConfig(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.address");
    }

    @Test
    void constructeur_quandServerAddressAbsente_alorsRefus() {
        // Given
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("mcp-local");
        // When / Then
        assertThatThrownBy(() -> new McpLocalSecurityConfig(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.address");
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
