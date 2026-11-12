package com.example.library.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.library.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** RM-MCP-10 et RM-MCP-11 : avec mcp-local, seul /mcp est ouvert ; avertissement ; refus avec prod. */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mcp-local")
@Import(TestcontainersConfiguration.class)
class McpLocalSecurityTest {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
            "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    private static MockHttpServletRequestBuilder mcpPost() {
        return post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(INITIALIZE);
    }

    @Test
    void mcp_quandSansJwtEtProfilMcpLocal_alorsPasRejeteParLaSecurite() throws Exception {
        // When
        MvcResult result = mockMvc.perform(mcpPost()).andReturn();
        // Then
        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    void apiBooks_quandSansJwtEtProfilMcpLocal_alorsNonAuthentifie401() throws Exception {
        // When / Then : mcp-local n'ouvre que /mcp
        mockMvc.perform(post("/api/v1/books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbn\":\"9782070368228\",\"title\":\"T\",\"author\":\"A\",\"copies\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void autreRoute_quandSansJwtEtProfilMcpLocal_alorsNonAuthentifie401() throws Exception {
        // When / Then
        mockMvc.perform(get("/api/v1/inconnu")).andExpect(status().isUnauthorized());
    }

    @Test
    void routeVoisineDeMcp_quandSansJwtEtProfilMcpLocal_alorsNonAuthentifie401() throws Exception {
        // When / Then : /mcpx n'est pas couvert par la chaine /mcp et /mcp/**
        mockMvc.perform(get("/mcpx")).andExpect(status().isUnauthorized());
    }

    @Test
    void contexte_quandProfilMcpLocalActif_alorsConfigurationPresente() {
        // When / Then
        assertThat(context.getBeansOfType(McpLocalSecurityConfig.class)).hasSize(1);
    }

    @Test
    void constructeur_quandProfilMcpLocal_alorsAvertissementJournalise(CapturedOutput output) {
        // Given
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("mcp-local");
        environment.setProperty("server.address", "127.0.0.1");
        // When
        new McpLocalSecurityConfig(environment);
        // Then
        assertThat(output.getAll()).contains("WARN").contains("Profil mcp-local actif")
                .contains("SANS authentification").contains("127.0.0.1");
    }

    @Test
    void constructeur_quandProfilProdActif_alorsRefusDeDemarrer() {
        // Given
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("mcp-local", "prod");
        environment.setProperty("server.address", "127.0.0.1");
        // When / Then : adresse valide, donc le refus vient bien du profil prod
        assertThatThrownBy(() -> new McpLocalSecurityConfig(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Le profil mcp-local ne peut pas être combiné avec le profil prod");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/mcp", "/mcp/x"})
    void mcp_quandOriginMalveillante_alorsInterdit403(String path) throws Exception {
        // When / Then
        mockMvc.perform(post(path).header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).content(INITIALIZE))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost.evil.com", "null", "http://127.0.0.1.evil.com"})
    void mcp_quandOriginDeConfusion_alorsInterdit403(String origin) throws Exception {
        // When / Then
        mockMvc.perform(mcpPost().header("Origin", origin)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:6274", "http://127.0.0.1"})
    void mcp_quandOriginLocale_alorsNiForbiddenNiUnauthorized(String origin) throws Exception {
        // When
        MvcResult result = mockMvc.perform(mcpPost().header("Origin", origin)).andReturn();
        // Then
        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    void mcp_quandSansOrigin_alorsNiForbiddenNiUnauthorized() throws Exception {
        // When
        MvcResult result = mockMvc.perform(mcpPost()).andReturn();
        // Then
        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }
}
