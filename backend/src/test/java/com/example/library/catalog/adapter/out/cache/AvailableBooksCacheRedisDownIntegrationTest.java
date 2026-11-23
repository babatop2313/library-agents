package com.example.library.catalog.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.UUID;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.catalog.application.port.in.ListAvailableBooksQuery;
import com.example.library.catalog.application.port.in.ListAvailableBooksUseCase;
import com.example.library.catalog.application.port.out.LoadAvailableBooksPort;
import com.example.library.catalog.domain.model.Book;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Redis injoignable (port fermé, jamais 6379) dans le contexte complet : l'enregistrement et la lecture réussissent
 * depuis la base, avec un WARN et sans écriture du cache. Couvre RC-11, RC-12 (D10), RC-15, RC-18.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AvailableBooksCacheRedisDownIntegrationTest {

    @DynamicPropertySource
    static void closedRedis(DynamicPropertyRegistry registry) throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> closedPort);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ListAvailableBooksUseCase listAvailableBooksUseCase;

    @Autowired
    @Qualifier("bookPersistenceAdapter")
    private LoadAvailableBooksPort persistenceLoad;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM book");
    }

    @AfterEach
    void cleanAfter() {
        jdbc.update("DELETE FROM book");
    }

    /** Partie fixe d'un gabarit SLF4J de production, avant le premier « {} » (suit toute modification du message). */
    private static String prefix(String template) {
        return template.substring(0, template.indexOf("{}"));
    }

    private static int count(String text, String fragment) {
        int occurrences = 0;
        for (int from = text.indexOf(fragment); from >= 0; from = text.indexOf(fragment, from + 1)) {
            occurrences++;
        }
        return occurrences;
    }

    @Test
    void postBooks_quandRedisIndisponible_alors201LivreEnBaseEtWarnInvalidation(CapturedOutput output)
            throws Exception {
        // Given : un LIBRARIAN et un Redis injoignable
        String body = """
                {"isbn":"9782070368228","title":"L'Étranger","author":"Albert Camus","copies":3}""";
        // When
        mockMvc.perform(post("/api/v1/books")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LIBRARIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                // Then : RC-15, l'enregistrement n'échoue pas
                .andExpect(status().isCreated());
        Integer inDatabase = jdbc.queryForObject("SELECT count(*) FROM book WHERE isbn = '9782070368228'",
                Integer.class);
        assertThat(inDatabase).isEqualTo(1);
        assertThat(count(output.getAll(), prefix(AvailableBooksCacheAdapter.LOG_EVICT_FAILED))).isEqualTo(1);
    }

    @Test
    void listAvailable_quandRedisIndisponible_alorsMemeResultatQueLaBaseEtWarnSansEcriture(CapturedOutput output) {
        // Given : trois livres en base
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO book (id, isbn, title, author, total_copies, available_copies, version)"
                    + " VALUES (?, ?, ?, 'Auteur', 1, 1, 0)", UUID.randomUUID(), "978000000%04d".formatted(i),
                    "Livre %02d".formatted(i));
        }
        // When
        List<Book> result = listAvailableBooksUseCase.listAvailable(ListAvailableBooksQuery.of(10));
        // Then : RC-11, D10 : même résultat que la base, un WARN de lecture, aucune écriture tentée
        assertThat(result).extracting(book -> book.getId().value())
                .isEqualTo(persistenceLoad.loadAvailableBooks(10).stream().map(book -> book.getId().value()).toList());
        assertThat(result).hasSize(3);
        assertThat(count(output.getAll(), prefix(AvailableBooksCacheAdapter.LOG_READ_FAILED))).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(prefix(AvailableBooksCacheAdapter.LOG_WRITE_FAILED));
    }

    @Test
    void getBooks_quandRedisIndisponible_alors200MemeContenuQueLaBaseEtWarnSansEcriture(CapturedOutput output)
            throws Exception {
        // Given : 25 livres en base, Redis injoignable
        for (int i = 0; i < 25; i++) {
            jdbc.update("INSERT INTO book (id, isbn, title, author, total_copies, available_copies, version)"
                    + " VALUES (?, ?, ?, 'Auteur', 1, 1, 0)", UUID.randomUUID(), "978000000%04d".formatted(i),
                    "Livre %02d".formatted(i));
        }
        // When / Then : RM-CAT-24, la requête réussit avec le contenu de la base
        mockMvc.perform(get("/api/v1/books").param("size", "10")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Livre 00"))
                .andExpect(jsonPath("$.content[9].title").value("Livre 09"))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3));
        assertThat(count(output.getAll(), prefix(AvailableBooksCacheAdapter.LOG_READ_FAILED))).isGreaterThanOrEqualTo(1);
    }

    @Test
    void getBooks_quandRedisIndisponibleEtRecherche_alors200SansWarnDeCache(CapturedOutput output) throws Exception {
        // Given
        jdbc.update("INSERT INTO book (id, isbn, title, author, total_copies, available_copies, version)"
                + " VALUES (?, '9780000000001', 'La Peste', 'Albert Camus', 1, 1, 0)", UUID.randomUUID());
        // When / Then : la recherche ne passe jamais par Redis
        mockMvc.perform(get("/api/v1/books").param("q", "camus")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        assertThat(count(output.getAll(), prefix(AvailableBooksCacheAdapter.LOG_READ_FAILED))).isZero();
    }
}
