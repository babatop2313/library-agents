package com.example.library.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration.RedisTestCache;
import com.example.library.config.security.TestJwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Critères d'acceptation de GET /api/v1/books dans le contexte complet : PostgreSQL et Redis réels, sécurité réelle,
 * JWT forgés par {@link TestJwts}. Couvre RM-04, RM-CAT-13 à RM-CAT-25 et RM-CAT-42.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
class CatalogApiIntegrationTest {

    private static final String URL = "/api/v1/books";
    private static final String CACHE_KEY = "library:catalog:available-books:v1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisTestCache redisTestCache;

    private int sequence;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM book");
        redisTestCache.flushAll();
    }

    @AfterEach
    void cleanAfter() {
        jdbc.update("DELETE FROM book");
        redisTestCache.flushAll();
    }

    // ------------------------------------------------------------------ aides

    private void givenBook(String title, String author, int total, int available) {
        jdbc.update("INSERT INTO book (id, isbn, title, author, total_copies, available_copies, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0)",
                UUID.randomUUID(), "978200000%04d".formatted(sequence++), title, author, total, available);
    }

    private void givenAvailableBooks(int count) {
        for (int i = 0; i < count; i++) {
            givenBook("Livre %02d".formatted(i), "Auteur", 2, 2);
        }
    }

    private ResultActions getAsMember(String query) throws Exception {
        return mockMvc.perform(get(URL + query).header("Authorization", TestJwts.bearer(TestJwts.member())));
    }

    private ResultActions getAsLibrarian(String query) throws Exception {
        return mockMvc.perform(get(URL + query).header("Authorization", TestJwts.bearer(TestJwts.librarian())));
    }

    private static List<String> expectedTitles(int from, int toExclusive) {
        return java.util.stream.IntStream.range(from, toExclusive).mapToObj("Livre %02d"::formatted).toList();
    }

    // ------------------------------------------------------------------ API : consultation

    @Test
    void getBooks_quandVingtCinqLivresDisponibles_alorsPremierePageDixLivresTrie() throws Exception {
        // Given
        givenAvailableBooks(25);
        // When / Then
        getAsMember("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(10)))
                .andExpect(jsonPath("$.content[*].title", contains(expectedTitles(0, 10).toArray())))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].id").isNotEmpty())
                .andExpect(jsonPath("$.content[0].isbn").isNotEmpty())
                .andExpect(jsonPath("$.content[0].author").value("Auteur"))
                .andExpect(jsonPath("$.content[0].totalCopies").value(2))
                .andExpect(jsonPath("$.content[0].availableCopies").value(2));
    }

    @Test
    void getBooks_quandPageDeuxTailleDix_alorsLesLivresDeRangVingtEtUnAVingtCinq() throws Exception {
        // Given
        givenAvailableBooks(25);
        // When / Then
        getAsMember("?page=2&size=10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", contains(expectedTitles(20, 25).toArray())))
                .andExpect(jsonPath("$.page").value(2));
    }

    @Test
    void getBooks_quandPagesSuccessives_alorsSansChevauchementNiPerte() throws Exception {
        // Given
        givenAvailableBooks(25);
        // When
        List<String> all = new java.util.ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String json = getAsMember("?page=" + page + "&size=10").andReturn().getResponse().getContentAsString();
            all.addAll(com.jayway.jsonpath.JsonPath.read(json, "$.content[*].title"));
        }
        // Then : RM-CAT-16
        assertThat(all).containsExactlyElementsOf(expectedTitles(0, 25));
    }

    @Test
    void getBooks_quandPageAuDelaDeLaDerniere_alors200ContenuVideEtTotalCorrect() throws Exception {
        // Given
        givenAvailableBooks(25);
        // When / Then
        getAsMember("?page=9&size=10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void getBooks_quandLivreSansExemplaireDisponible_alorsExcluDuContenuEtDuTotal() throws Exception {
        // Given
        givenBook("Epuise", "A", 2, 0);
        givenBook("Alpha", "A", 2, 1);
        givenBook("Beta", "A", 2, 2);
        // When / Then
        getAsMember("")
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].title", contains("Alpha", "Beta")));
    }

    @Test
    void getBooks_quandAucunLivreDisponible_alors200ToutAZero() throws Exception {
        // Given
        givenBook("Epuise", "A", 2, 0);
        // When / Then
        getAsMember("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void getBooks_quandTriParCasseEtIsbn_alorsOrdreTitreSansCassePuisIsbn() throws Exception {
        // Given
        givenBook("bravo", "A", 1, 1);
        givenBook("Bravo", "A", 1, 1);
        givenBook("alpha", "A", 1, 1);
        // When
        String json = getAsMember("").andReturn().getResponse().getContentAsString();
        // Then : « alpha », puis les deux « bravo » départagés par ISBN (ordre d'insertion = ordre d'ISBN ici)
        List<String> titles = com.jayway.jsonpath.JsonPath.read(json, "$.content[*].title");
        assertThat(titles).containsExactly("alpha", "bravo", "Bravo");
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> searches() {
        List<String> camus = List.of("L'Étranger", "La Peste");
        List<String> all = List.of("Germinal", "L'Étranger", "La Peste");
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("camus", camus),
                org.junit.jupiter.params.provider.Arguments.of("CAMUS", camus),
                org.junit.jupiter.params.provider.Arguments.of("peste", List.of("La Peste")),
                org.junit.jupiter.params.provider.Arguments.of("germ", List.of("Germinal")),
                org.junit.jupiter.params.provider.Arguments.of("ZOLA", List.of("Germinal")),
                org.junit.jupiter.params.provider.Arguments.of("introuvab", List.of()),
                org.junit.jupiter.params.provider.Arguments.of("", all),
                org.junit.jupiter.params.provider.Arguments.of("   ", all));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("searches")
    void getBooks_quandRecherche_alorsTitresEtTotalAttendus(String q, List<String> expected) throws Exception {
        // Given
        givenBook("L'Étranger", "Albert Camus", 2, 2);
        givenBook("La Peste", "Albert Camus", 2, 1);
        givenBook("Germinal", "Émile Zola", 2, 2);
        // When
        ResultActions result = mockMvc.perform(get(URL).param("q", q)
                .header("Authorization", TestJwts.bearer(TestJwts.member())));
        // Then
        result.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(expected.size()));
        String json = result.andReturn().getResponse().getContentAsString();
        List<String> actual = com.jayway.jsonpath.JsonPath.read(json, "$.content[*].title");
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void getBooks_quandRechercheSurIsbn_alorsAucunResultat() throws Exception {
        // Given
        givenBook("Titre", "Auteur", 1, 1);
        String isbn = jdbc.queryForObject("SELECT isbn FROM book", String.class);
        // When / Then
        mockMvc.perform(get(URL).param("q", isbn).header("Authorization", TestJwts.bearer(TestJwts.member())))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getBooks_quandCaracteresSpeciauxDansQ_alorsTraitesCommeDuTexte() throws Exception {
        // Given
        givenBook("100% Python", "Auteur", 1, 1);
        givenBook("Le Python", "Auteur", 1, 1);
        givenBook("snake_case", "Auteur", 1, 1);
        givenBook("C:\\temp", "Auteur", 1, 1);
        // When / Then : « 100% », « % », « _ » et « \ » (MockMvc encode les paramètres : équivalent à q=100%25)
        mockMvc.perform(get(URL).param("q", "100%").header("Authorization", TestJwts.bearer(TestJwts.member())))
                .andExpect(jsonPath("$.content[*].title", contains("100% Python")));
        mockMvc.perform(get(URL).param("q", "%").header("Authorization", TestJwts.bearer(TestJwts.member())))
                .andExpect(jsonPath("$.content[*].title", contains("100% Python")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get(URL).param("q", "_").header("Authorization", TestJwts.bearer(TestJwts.member())))
                .andExpect(jsonPath("$.content[*].title", contains("snake_case")));
        mockMvc.perform(get(URL).param("q", "\\").header("Authorization", TestJwts.bearer(TestJwts.member())))
                .andExpect(jsonPath("$.content[*].title", contains("C:\\temp")));
    }

    @Test
    void getBooks_quandRechercheEtPaginationCombinees_alorsFiltreAvantPagination() throws Exception {
        // Given : 15 livres de Hugo parmi 30
        for (int i = 0; i < 15; i++) {
            givenBook("Roman %02d".formatted(i), "Victor Hugo", 1, 1);
        }
        for (int i = 15; i < 30; i++) {
            givenBook("Roman %02d".formatted(i), "Autre", 1, 1);
        }
        // When / Then
        getAsMember("?q=hugo&size=10&page=1")
                .andExpect(jsonPath("$.content", hasSize(5)))
                .andExpect(jsonPath("$.totalElements").value(15))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @ParameterizedTest
    @CsvSource({"page=-1,page", "page=abc,page", "size=0,size", "size=51,size", "size=abc,size"})
    void getBooks_quandPaginationInvalide_alors400ValidationFailedSurLeChamp(String param, String field)
            throws Exception {
        // Given / When / Then
        getAsMember("?" + param)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='" + field + "')]", hasSize(1)));
    }

    @Test
    void getBooks_quandQDeCentUnCaracteres_alors400SurQ() throws Exception {
        // Given / When / Then
        getAsMember("?q=" + "a".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("q"));
    }

    @Test
    void getBooks_quandTriParametre_alorsIgnoreEtOrdreInchange() throws Exception {
        // Given
        givenBook("Alpha", "Zorro", 1, 1);
        givenBook("Beta", "Adam", 1, 1);
        // When / Then
        getAsMember("?sort=author,desc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", contains("Alpha", "Beta")));
    }

    @Test
    void getBooks_quandSansAuthentification_alors401ProblemDetailUnauthenticated() throws Exception {
        // Given / When / Then
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void getBooks_quandJwtExpireOuMalSigne_alors401() throws Exception {
        // Given / When / Then
        mockMvc.perform(get(URL).header("Authorization", TestJwts.bearer(TestJwts.expired())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).header("Authorization", TestJwts.bearer(TestJwts.signedWithOtherKey())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getBooks_quandJwtValideSansRoleLibrarianNiMember_alors403AccessDenied() throws Exception {
        // Given
        String token = TestJwts.withRoles(UUID.randomUUID().toString(), "autre@example.org", "AUDITOR");
        // When / Then
        mockMvc.perform(get(URL).header("Authorization", TestJwts.bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void getBooks_quandCookieAccessTokenSansCsrf_alors200() throws Exception {
        // Given : session navigateur, requête GET sans X-XSRF-TOKEN
        givenAvailableBooks(1);
        // When / Then : RM-CAT-13, pas de CSRF pour une lecture
        mockMvc.perform(get(URL).cookie(new Cookie("ACCESS_TOKEN", TestJwts.member())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getBooks_quandCookieAccessTokenInvalide_alors401() throws Exception {
        // Given / When / Then
        mockMvc.perform(get(URL).cookie(new Cookie("ACCESS_TOKEN", TestJwts.expired())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getBooks_quandLibrarianPuisMember_alorsMemeContenu() throws Exception {
        // Given
        givenAvailableBooks(12);
        // When
        String asLibrarian = getAsLibrarian("?page=0&size=10").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String asMember = getAsMember("?page=0&size=10").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Then
        assertThat(asMember).isEqualTo(asLibrarian);
    }

    @Test
    void getBooks_quandUtilisateurAvecLesDeuxRoles_alors200() throws Exception {
        // Given
        String token = TestJwts.withRoles(UUID.randomUUID().toString(), "both@example.org", "LIBRARIAN", "MEMBER");
        // When / Then
        mockMvc.perform(get(URL).header("Authorization", TestJwts.bearer(token))).andExpect(status().isOk());
    }

    @Test
    void getBooks_quandPostSurLaMemeUrlParUnMember_alorsToujours403() throws Exception {
        // Given : non-régression de POST (RM-CAT-13 n'ouvre pas l'écriture)
        // When / Then
        mockMvc.perform(post(URL).header("Authorization", TestJwts.bearer(TestJwts.member()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbn\":\"9782070368228\",\"title\":\"T\",\"author\":\"A\",\"copies\":1}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ API : cache (RM-CAT-24, 25, 42)

    @Test
    void getBooks_quandTeteDeListe_alorsServieParLeCacheEtTotalLuEnBase() throws Exception {
        // Given : 30 livres ; un premier appel remplit le cache
        givenAvailableBooks(30);
        String first = getAsMember("?page=0&size=10").andReturn().getResponse().getContentAsString();
        assertThat(redis.hasKey(CACHE_KEY)).isTrue();
        // When : la base change derrière le cache (renommage du premier livre, retrait d'un livre en fin de liste)
        jdbc.update("UPDATE book SET title = 'Livre 00 modifie' WHERE title = 'Livre 00'");
        jdbc.update("DELETE FROM book WHERE title = 'Livre 29'");
        // Then : le contenu vient du cache (ancien titre), le total de la base (29)
        getAsMember("?page=0&size=10")
                .andExpect(jsonPath("$.content[*].title", contains(expectedTitles(0, 10).toArray())))
                .andExpect(jsonPath("$.totalElements").value(29));
        assertThat(first).contains("\"title\":\"Livre 00\"");
    }

    @Test
    void getBooks_quandContenuAvecEtSansCache_alorsIdentique() throws Exception {
        // Given
        givenAvailableBooks(30);
        // When : premier appel = lecture base (puis remplissage), second = cache
        String fromDatabase = getAsMember("?page=1&size=10").andReturn().getResponse().getContentAsString();
        String fromCache = getAsMember("?page=1&size=10").andReturn().getResponse().getContentAsString();
        // Then
        assertThat(fromCache).isEqualTo(fromDatabase);
    }

    @Test
    void getBooks_quandRecherche_alorsLuEnBaseEtCacheNonModifie() throws Exception {
        // Given : liste en cache
        givenAvailableBooks(30);
        givenBook("Camus complet", "Albert Camus", 1, 1);
        getAsMember("").andExpect(status().isOk());
        String cached = redis.opsForValue().get(CACHE_KEY);
        assertThat(cached).isNotNull();
        // When : la base change, puis recherche
        jdbc.update("UPDATE book SET title = 'Camus renomme' WHERE title = 'Camus complet'");
        // Then : la recherche voit la base, le cache est inchangé
        getAsMember("?q=camus")
                .andExpect(jsonPath("$.content[*].title", contains("Camus renomme")));
        assertThat(redis.opsForValue().get(CACHE_KEY)).isEqualTo(cached);
    }

    @Test
    void getBooks_quandPageProfonde_alorsLueEnBaseEtCorrecte() throws Exception {
        // Given : liste en cache puis modification en base d'un livre de la page 2
        givenAvailableBooks(30);
        getAsMember("").andExpect(status().isOk());
        jdbc.update("UPDATE book SET author = 'Nouveau' WHERE title = 'Livre 25'");
        // When / Then
        getAsMember("?page=2&size=10")
                .andExpect(jsonPath("$.content[*].title", contains(expectedTitles(20, 30).toArray())))
                .andExpect(jsonPath("$.content[5].author").value("Nouveau"));
    }

    @Test
    void getBooks_quandLivreEnregistreApresMiseEnCache_alorsPresentALaLectureSuivante() throws Exception {
        // Given : liste en cache sans le livre
        givenAvailableBooks(3);
        getAsMember("").andExpect(jsonPath("$.totalElements").value(3));
        assertThat(redis.hasKey(CACHE_KEY)).isTrue();
        // When : un LIBRARIAN enregistre le livre (201), invalidation après commit
        mockMvc.perform(post(URL).header("Authorization", TestJwts.bearer(TestJwts.librarian()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbn\":\"9782070368228\",\"title\":\"Zorba le Grec\",\"author\":\"Kazantzaki\","
                                + "\"copies\":3}"))
                .andExpect(status().isCreated());
        // Then : RM-CAT-25, RM-CAT-42 : visible par recherche et par la liste de tête
        assertThat(redis.hasKey(CACHE_KEY)).isFalse();
        getAsMember("?q=zorba")
                .andExpect(jsonPath("$.content[*].isbn", contains("9782070368228")));
        getAsMember("?size=50")
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[*].title", hasItem("Zorba le Grec")));
    }

    @Test
    void getBooks_quandEnregistrementEnEchec409_alorsCacheConserve() throws Exception {
        // Given : un livre existant, cache rempli
        givenBook("Existant", "A", 1, 1);
        String isbn = jdbc.queryForObject("SELECT isbn FROM book", String.class);
        getAsMember("").andExpect(status().isOk());
        assertThat(redis.hasKey(CACHE_KEY)).isTrue();
        // When : POST du même ISBN
        mockMvc.perform(post(URL).header("Authorization", TestJwts.bearer(TestJwts.librarian()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbn\":\"" + isbn + "\",\"title\":\"T\",\"author\":\"A\",\"copies\":1}"))
                .andExpect(status().isConflict());
        // Then : rien n'a changé, la liste n'est pas invalidée
        assertThat(redis.hasKey(CACHE_KEY)).isTrue();
        getAsMember("").andExpect(jsonPath("$.content[*].title", not(hasItem("T"))));
    }
}
