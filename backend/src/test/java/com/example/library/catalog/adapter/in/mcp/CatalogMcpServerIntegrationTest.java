package com.example.library.catalog.adapter.in.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.library.TestcontainersConfiguration;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bout en bout : client MCP Streamable HTTP reel -> /mcp (JWT) -> outils -> use cases -> PostgreSQL (Testcontainers).
 * Constat documente : pour limit = "abc" / 2.5 et isbn absent, le SDK MCP rejette l'appel par validation de schema
 * avant le code de l'application ; isError=true, sans stack trace, mais le message est celui (anglais) du SDK.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CatalogMcpServerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private McpSyncClient client;

    @BeforeEach
    void connect() {
        jdbc.update("DELETE FROM book");
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .claim("roles", List.of("MEMBER"))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        client.closeGracefully();
        jdbc.update("DELETE FROM book");
    }

    private void givenBook(String isbn, String title, int total, int available) {
        jdbc.update("INSERT INTO book (id, isbn, title, author, total_copies, available_copies, version)"
                + " VALUES (?, ?, ?, 'Albert Camus', ?, ?, 0)", UUID.randomUUID(), isbn, title, total, available);
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private static void assertNoStackTrace(String message) {
        assertThat(message).doesNotContain("\tat ", "com.example", "java.lang", "Exception", "org.springframework");
    }

    @Test
    void listTools_quandClientConnecte_alorsExactementDeuxOutilsDecrits() {
        // When
        List<Tool> tools = client.listTools().tools();
        // Then
        assertThat(tools).extracting(Tool::name).containsExactlyInAnyOrder("get_book", "list_available_books");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.annotations().readOnlyHint()).isTrue();
        });
    }

    @Test
    void getBook_quandLivreExistant_alorsFicheJsonSansId() {
        // Given
        givenBook("9782070368228", "L'Étranger", 3, 2);
        // When
        CallToolResult result = client.callTool(new CallToolRequest("get_book", Map.of("isbn", "978-2-07-036822-8")));
        // Then
        assertThat(result.isError()).isFalse();
        JsonNode json = jsonMapper.readTree(text(result));
        assertThat(json.get("isbn").asString()).isEqualTo("9782070368228");
        assertThat(json.get("title").asString()).isEqualTo("L'Étranger");
        assertThat(json.get("availableCopies").asInt()).isEqualTo(2);
        assertThat(json.get("totalCopies").asInt()).isEqualTo(3);
        assertThat(json.has("id")).isFalse();
    }

    @Test
    void getBook_quandLivreAbsent_alorsErreurDOutilEnFrancais() {
        // When
        CallToolResult result = client.callTool(new CallToolRequest("get_book", Map.of("isbn", "9780000000002")));
        // Then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("Aucun livre trouvé pour l'ISBN 9780000000002.");
    }

    @Test
    void getBook_quandIsbnInvalide_alorsErreurDOutilEnFrancais() {
        // When
        CallToolResult result = client.callTool(new CallToolRequest("get_book", Map.of("isbn", "978207036822")));
        // Then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("13 chiffres");
        assertNoStackTrace(text(result));
    }

    @Test
    void getBook_quandIsbnAbsent_alorsErreurDuSdkSansStackTrace() {
        // When
        CallToolResult result = client.callTool(new CallToolRequest("get_book", Map.of()));
        // Then
        assertThat(result.isError()).isTrue();
        System.out.println("[CONSTAT] get_book sans isbn -> " + text(result));
        assertThat(text(result)).isNotBlank();
        assertNoStackTrace(text(result));
    }

    @Test
    void listAvailableBooks_quandCinqLivresDontTroisDisponibles_alorsTroisTries() {
        // Given
        givenBook("9782070368228", "zebre", 2, 1);
        givenBook("9782070368235", "epuise", 2, 0);
        givenBook("9782070368242", "Alpha", 1, 1);
        givenBook("9782070368259", "Epuise aussi", 1, 0);
        givenBook("9782070368266", "beta", 4, 4);
        // When
        CallToolResult result = client.callTool(new CallToolRequest("list_available_books", Map.of()));
        // Then
        assertThat(result.isError()).isFalse();
        JsonNode json = jsonMapper.readTree(text(result));
        assertThat(json).hasSize(3);
        assertThat(json.get(0).get("title").asString()).isEqualTo("Alpha");
        assertThat(json.get(1).get("title").asString()).isEqualTo("beta");
        assertThat(json.get(2).get("title").asString()).isEqualTo("zebre");
    }

    @Test
    void listAvailableBooks_quandTrenteLivresEtLimiteAbsente_alorsDixLivres() {
        // Given
        for (int i = 0; i < 30; i++) {
            givenBook("978000000%04d".formatted(i), "Livre %02d".formatted(i), 1, 1);
        }
        // When
        CallToolResult result = client.callTool(new CallToolRequest("list_available_books", Map.of()));
        // Then
        assertThat(jsonMapper.readTree(text(result))).hasSize(10);
    }

    @Test
    void listAvailableBooks_quandLimiteCinqEtVingt_alorsRespectee() {
        // Given
        for (int i = 0; i < 30; i++) {
            givenBook("978000000%04d".formatted(i), "Livre %02d".formatted(i), 1, 1);
        }
        // When
        CallToolResult five = client.callTool(new CallToolRequest("list_available_books", Map.of("limit", 5)));
        CallToolResult twenty = client.callTool(new CallToolRequest("list_available_books", Map.of("limit", 20)));
        // Then
        assertThat(jsonMapper.readTree(text(five))).hasSize(5);
        assertThat(jsonMapper.readTree(text(twenty))).hasSize(20);
    }

    @Test
    void listAvailableBooks_quandCatalogueVide_alorsSuccesListeVide() {
        // When
        CallToolResult result = client.callTool(new CallToolRequest("list_available_books", Map.of()));
        // Then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).isEqualTo("[]");
    }

    @Test
    void listAvailableBooks_quandLimiteHorsBornes_alorsErreurDOutilEnFrancais() {
        // When
        CallToolResult zero = client.callTool(new CallToolRequest("list_available_books", Map.of("limit", 0)));
        CallToolResult tooBig = client.callTool(new CallToolRequest("list_available_books", Map.of("limit", 21)));
        // Then
        assertThat(zero.isError()).isTrue();
        assertThat(text(zero)).isEqualTo("limit doit être un entier compris entre 1 et 20 (10 par défaut si absent).");
        assertThat(tooBig.isError()).isTrue();
        assertThat(text(tooBig)).contains("entre 1 et 20");
    }

    @Test
    void listAvailableBooks_quandLimiteTexteAbc_alorsErreurDuSdkSansStackTrace() {
        // When
        CallToolResult result = client.callTool(new CallToolRequest("list_available_books", Map.of("limit", "abc")));
        // Then
        assertThat(result.isError()).isTrue();
        System.out.println("[CONSTAT] list_available_books limit=\"abc\" -> " + text(result));
        assertThat(text(result)).isNotBlank();
        assertNoStackTrace(text(result));
    }

    @Test
    void listAvailableBooks_quandLimiteDecimale_alorsErreurDuSdkSansStackTrace() {
        // When
        CallToolResult result = client.callTool(new CallToolRequest("list_available_books", Map.of("limit", 2.5)));
        // Then
        assertThat(result.isError()).isTrue();
        System.out.println("[CONSTAT] list_available_books limit=2.5 -> " + text(result));
        assertThat(text(result)).isNotBlank();
        assertNoStackTrace(text(result));
    }
}
