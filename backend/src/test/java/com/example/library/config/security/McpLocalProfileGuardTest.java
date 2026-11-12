package com.example.library.config.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** Decision 4 et 14 : mcp-local ne cohabite jamais avec prod et n'écoute que sur 127.0.0.1 ou localhost. */
class McpLocalProfileGuardTest {

    private static final String PROD_MESSAGE = "Le profil mcp-local ne peut pas être combiné avec le profil prod";

    @Test
    void check_quandMcpLocalEtProd_alorsRefus() {
        // When / Then
        assertThatThrownBy(() -> McpLocalProfileGuard.check(new String[] {"mcp-local", "prod"}, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(PROD_MESSAGE);
    }

    @Test
    void check_quandProdEnMajusculesAvantMcpLocal_alorsRefus() {
        // When / Then
        assertThatThrownBy(() -> McpLocalProfileGuard.check(new String[] {"PROD", "mcp-local"}, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(PROD_MESSAGE);
    }

    @Test
    void check_quandProdAvecEspaces_alorsRefus() {
        // When / Then
        assertThatThrownBy(() -> McpLocalProfileGuard.check(new String[] {"mcp-local", " Prod "}, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(PROD_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "localhost", "LOCALHOST", " localhost "})
    void check_quandAdresseLocaleAutorisee_alorsAccepte(String address) {
        // When / Then
        assertThatCode(() -> McpLocalProfileGuard.check(new String[] {"mcp-local"}, address))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "::1", "0.0.0.0", "127.0.0.2", "192.168.1.10"})
    void check_quandAdresseAbsenteOuNonAutorisee_alorsRefus(String address) {
        // When / Then
        assertThatThrownBy(() -> McpLocalProfileGuard.check(new String[] {"mcp-local"}, address))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Le profil mcp-local exige server.address égal à 127.0.0.1 ou à localhost");
    }

    @Test
    void check_quandProdAvecAdresseValide_alorsRefusProd() {
        // When / Then
        assertThatThrownBy(() -> McpLocalProfileGuard.check(new String[] {"mcp-local", "prod"}, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(PROD_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::1"})
    void constructeur_quandServerAddressNonAutorisee_alorsRefus(String address) {
        // Given : un écrasement de server.address (variable d'environnement, ligne de commande) vu par l'Environment
        MockEnvironment environment = new MockEnvironment().withProperty("server.address", address);
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
        assertThatCode(() -> McpLocalProfileGuard.check(new String[] {"mcp-local"}, "127.0.0.1"))
                .doesNotThrowAnyException();
    }

    @Test
    void check_quandMcpLocalEtLocal_alorsAccepte() {
        // When / Then
        assertThatCode(() -> McpLocalProfileGuard.check(new String[] {"mcp-local", "local"}, "127.0.0.1"))
                .doesNotThrowAnyException();
    }

    @Test
    void check_quandProfilNomVoisinDeProd_alorsAccepte() {
        // When / Then : seul le nom exact "prod" est interdit
        assertThatCode(() -> McpLocalProfileGuard.check(
                        new String[] {"mcp-local", "production", "preprod"}, "127.0.0.1"))
                .doesNotThrowAnyException();
    }
}
