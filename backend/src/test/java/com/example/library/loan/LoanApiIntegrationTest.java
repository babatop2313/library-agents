package com.example.library.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration.RedisTestCache;
import com.example.library.config.security.TestJwts;
import com.example.library.loan.support.FixedClockTestConfiguration;
import com.example.library.loan.support.MutableClock;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
 * Critères d'acceptation de l'API des emprunts dans le contexte complet : PostgreSQL et Redis réels, sécurité réelle,
 * JWT forgés par {@link TestJwts}, horloge fixée au 2026-11-24 (Africa/Dakar). Couvre RM-01 à RM-05, RM-LOAN-01 à 07,
 * 10 à 14, 16, 18, 19, 28, 29, 31, 32.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class,
        FixedClockTestConfiguration.class})
class LoanApiIntegrationTest {

    private static final String URL = "/api/v1/loans";
    private static final String BOOKS_URL = "/api/v1/books";
    private static final String CACHE_KEY = "library:catalog:available-books:v1";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisTestCache redisTestCache;

    @Autowired
    private MutableClock clock;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private int sequence;

    /** Un membre de test : identifiant (sub du jeton) et jeton. */
    private record Member(UUID id, String bearer) {
    }

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM loan");
        jdbc.update("DELETE FROM book");
        redisTestCache.flushAll();
        clock.reset();
    }

    // ------------------------------------------------------------------ aides

    private static Member newMember() {
        UUID id = UUID.randomUUID();
        return new Member(id, TestJwts.bearer(TestJwts.withRoles(id.toString(), "membre@example.org", "MEMBER")));
    }

    private UUID givenBook(String title, int total, int available) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO book (id, isbn, title, author, total_copies, available_copies, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0)",
                id, "978300000%04d".formatted(sequence++), title, "Auteur", total, available);
        return id;
    }

    /** Emprunt actif inséré directement (échéance relative à la date du jour). */
    private UUID givenActiveLoan(Member member, UUID book, int dueInDays) {
        LocalDate dueOn = TODAY.plusDays(dueInDays);
        return insertLoan(member, book, dueOn.minusDays(14), dueOn, null);
    }

    private UUID givenReturnedLoan(Member member, UUID book) {
        return insertLoan(member, book, TODAY.minusDays(10), TODAY.plusDays(4), TODAY.minusDays(2));
    }

    private UUID insertLoan(Member member, UUID book, LocalDate borrowedOn, LocalDate dueOn, LocalDate returnedOn) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO loan (id, member_id, book_id, borrowed_on, due_on, returned_on)"
                        + " VALUES (?, ?, ?, ?, ?, ?)", id, member.id(), book, borrowedOn, dueOn, returnedOn);
        return id;
    }

    private int available(UUID book) {
        return jdbc.queryForObject("SELECT available_copies FROM book WHERE id = ?", Integer.class, book);
    }

    private int loanCount() {
        return jdbc.queryForObject("SELECT count(*) FROM loan", Integer.class);
    }

    private int activeLoanCount(Member member) {
        return jdbc.queryForObject("SELECT count(*) FROM loan WHERE member_id = ? AND returned_on IS NULL",
                Integer.class, member.id());
    }

    private ResultActions borrow(Member member, UUID book) throws Exception {
        return borrowRaw(member.bearer(), "{\"bookId\":\"" + book + "\"}");
    }

    private ResultActions borrowRaw(String authorization, String body) throws Exception {
        return mockMvc.perform(post(URL).header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions list(Member member) throws Exception {
        return mockMvc.perform(get(URL).header("Authorization", member.bearer()));
    }

    private ResultActions giveBack(Member member, Object loanId) throws Exception {
        return mockMvc.perform(post(URL + "/" + loanId + "/return").header("Authorization", member.bearer()));
    }

    private List<String> listedBookIds(Member viewer) throws Exception {
        String json = mockMvc.perform(get(BOOKS_URL).header("Authorization", viewer.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.content[*].id");
    }

    private UUID borrowAndGetLoanId(Member member, UUID book) throws Exception {
        String json = borrow(member, book).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    // ------------------------------------------------------------------ emprunter : nominal

    @Test
    void borrow_quandMembreSansEmpruntEtLivreDisponible_alors201LocationEtCorpsDuContrat() throws Exception {
        // Given : le 2026-11-24, « L'Étranger » à 2 exemplaires disponibles
        Member member = newMember();
        UUID book = givenBook("L'Étranger", 2, 2);
        // When
        String json = borrow(member, book)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(".*/api/v1/loans/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.bookId").value(book.toString()))
                .andExpect(jsonPath("$.borrowedOn").value("2026-11-24"))
                .andExpect(jsonPath("$.dueOn").value("2026-12-08"))
                .andReturn().getResponse().getContentAsString();
        // Then : un exemplaire de moins, l'emprunt est en base pour le sub du jeton
        String id = JsonPath.read(json, "$.id");
        assertThat(available(book)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT member_id FROM loan WHERE id = ?", UUID.class, UUID.fromString(id)))
                .isEqualTo(member.id());
        assertThat(jdbc.queryForObject("SELECT total_copies FROM book WHERE id = ?", Integer.class, book)).isEqualTo(2);
    }

    @Test
    void borrow_quandFuseauAsiaTokyoEtInstant20hUtc_alorsDateDuLendemain() throws Exception {
        // Given : app.timezone = Asia/Tokyo, instant 2026-11-24T20:00:00Z (RM-LOAN-29)
        clock.set(Instant.parse("2026-11-24T20:00:00Z"), ZoneId.of("Asia/Tokyo"));
        Member member = newMember();
        UUID book = givenBook("Livre", 1, 1);
        // When / Then
        borrow(member, book)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.borrowedOn").value("2026-11-25"))
                .andExpect(jsonPath("$.dueOn").value("2026-12-09"));
    }

    @Test
    void borrow_quandDernierExemplaire_alorsStockAZeroEtLivreAbsentDeLaListeDuCatalogue() throws Exception {
        // Given : un livre à 1 exemplaire, la liste du catalogue est en cache et contient ce livre
        Member member = newMember();
        UUID book = givenBook("Dernier", 1, 1);
        assertThat(listedBookIds(member)).contains(book.toString());
        assertThat(redis.hasKey(CACHE_KEY)).isTrue();
        // When
        borrow(member, book).andExpect(status().isCreated());
        // Then : RM-LOAN-16, la clé est invalidée après le commit et le livre disparaît de GET /api/v1/books
        assertThat(available(book)).isZero();
        assertThat(redis.hasKey(CACHE_KEY)).isFalse();
        assertThat(listedBookIds(member)).doesNotContain(book.toString());
    }

    @Test
    void giveBack_quandRetourDuDernierExemplaire_alorsCleInvalideeEtLivreReapparait() throws Exception {
        // Given : livre épuisé, emprunté par le membre ; la liste (sans ce livre) est en cache
        Member member = newMember();
        UUID book = givenBook("Épuisé puis rendu", 1, 0);
        givenBook("Toujours disponible", 1, 1);   // liste non vide : une liste vide n'est pas mise en cache
        UUID loan = givenActiveLoan(member, book, 7);
        assertThat(listedBookIds(member)).doesNotContain(book.toString());
        assertThat(redis.hasKey(CACHE_KEY)).isTrue();
        // When
        giveBack(member, loan).andExpect(status().isNoContent());
        // Then : RM-LOAN-16
        assertThat(redis.hasKey(CACHE_KEY)).isFalse();
        assertThat(listedBookIds(member)).contains(book.toString());
        assertThat(available(book)).isEqualTo(1);
    }

    @Test
    void borrow_quandRefusMetier_alorsCleDuCacheConservee() throws Exception {
        // Given : liste en cache, livre épuisé
        Member member = newMember();
        UUID soldOut = givenBook("Épuisé", 1, 0);
        givenBook("Disponible", 1, 1);
        listedBookIds(member);
        assertThat(redis.hasKey(CACHE_KEY)).isTrue();
        // When
        borrow(member, soldOut).andExpect(status().isConflict());
        // Then : aucune écriture, rien à invalider
        assertThat(redis.hasKey(CACHE_KEY)).isTrue();
    }

    // ------------------------------------------------------------------ emprunter : refus

    @Test
    void borrow_quandLivreIndisponible_alors409BookNotAvailableSansEmprunt() throws Exception {
        // Given
        Member member = newMember();
        UUID book = givenBook("Épuisé", 2, 0);
        // When / Then
        borrow(member, book)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BOOK_NOT_AVAILABLE"));
        assertThat(loanCount()).isZero();
        assertThat(available(book)).isZero();
    }

    @Test
    void borrow_quandLivreDejaEmpruntePourCeMembre_alors409BookAlreadyBorrowedSansChangement() throws Exception {
        // Given : « La Peste », 2 exemplaires dont 1 emprunté par ce membre
        Member member = newMember();
        UUID book = givenBook("La Peste", 2, 1);
        givenActiveLoan(member, book, 7);
        // When / Then
        borrow(member, book)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOK_ALREADY_BORROWED"));
        assertThat(loanCount()).isEqualTo(1);
        assertThat(available(book)).isEqualTo(1);
    }

    @Test
    void borrow_quandMemeLivreApresRetour_alors201() throws Exception {
        // Given : emprunté puis rendu
        Member member = newMember();
        UUID book = givenBook("La Peste", 2, 2);
        UUID first = borrowAndGetLoanId(member, book);
        giveBack(member, first).andExpect(status().isNoContent());
        // When / Then
        borrow(member, book).andExpect(status().isCreated());
        assertThat(loanCount()).isEqualTo(2);
        assertThat(available(book)).isEqualTo(1);
    }

    @Test
    void borrow_quandAutreMembreAEmprunteLeMemeLivre_alors201() throws Exception {
        // Given : membre A a emprunté « La Peste » (2 exemplaires, 1 disponible)
        Member a = newMember();
        Member b = newMember();
        UUID book = givenBook("La Peste", 2, 1);
        givenActiveLoan(a, book, 7);
        // When / Then
        borrow(b, book).andExpect(status().isCreated());
        assertThat(available(book)).isZero();
    }

    @Test
    void borrow_quandQuatriemeEmprunt_alors409LoanLimitReached() throws Exception {
        // Given : 3 emprunts actifs non en retard
        Member member = newMember();
        for (int i = 0; i < 3; i++) {
            givenActiveLoan(member, givenBook("Livre " + i, 1, 0), 7);
        }
        UUID fourth = givenBook("Quatrième", 2, 2);
        // When / Then
        borrow(member, fourth)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_LIMIT_REACHED"));
        assertThat(available(fourth)).isEqualTo(2);
        assertThat(activeLoanCount(member)).isEqualTo(3);
    }

    @Test
    void borrow_quandLimiteLibereeParUnRetour_alors201() throws Exception {
        // Given : 3 emprunts actifs
        Member member = newMember();
        UUID first = givenActiveLoan(member, givenBook("Livre 0", 1, 0), 7);
        givenActiveLoan(member, givenBook("Livre 1", 1, 0), 7);
        givenActiveLoan(member, givenBook("Livre 2", 1, 0), 7);
        UUID other = givenBook("Autre", 1, 1);
        // When : il rend l'un d'eux puis emprunte
        giveBack(member, first).andExpect(status().isNoContent());
        // Then
        borrow(member, other).andExpect(status().isCreated());
        assertThat(activeLoanCount(member)).isEqualTo(3);
    }

    @Test
    void borrow_quandEmpruntEnRetard_alors409MemberHasOverdueLoan() throws Exception {
        // Given : échéance hier
        Member member = newMember();
        givenActiveLoan(member, givenBook("En retard", 1, 0), -1);
        UUID target = givenBook("Cible", 1, 1);
        // When / Then
        borrow(member, target)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_HAS_OVERDUE_LOAN"));
        assertThat(available(target)).isEqualTo(1);
    }

    @Test
    void borrow_quandEcheanceAujourdhui_alorsPasEnRetardEtEmpruntPossible() throws Exception {
        // Given
        Member member = newMember();
        givenActiveLoan(member, givenBook("Échéance du jour", 1, 0), 0);
        UUID target = givenBook("Cible", 1, 1);
        // When / Then
        borrow(member, target).andExpect(status().isCreated());
    }

    @Test
    void borrow_quandRetardRenduPuisEmprunt_alorsRetour204Puis201() throws Exception {
        // Given : le seul emprunt du membre est en retard
        Member member = newMember();
        UUID late = givenActiveLoan(member, givenBook("En retard", 1, 0), -3);
        UUID target = givenBook("Cible", 1, 1);
        // When / Then : RM-LOAN-12, le retour lève le blocage immédiatement
        giveBack(member, late).andExpect(status().isNoContent());
        borrow(member, target).andExpect(status().isCreated());
    }

    static Stream<Arguments> evaluationOrder() {
        // retard, nombre d'emprunts actifs (hors livre visé), livre visé déjà emprunté, stock, code attendu
        return Stream.of(
                Arguments.of("retard + 3 actifs + déjà emprunté + 0 exemplaire", true, 2, true, 0,
                        409, "MEMBER_HAS_OVERDUE_LOAN"),
                Arguments.of("aucun retard + 3 actifs dont ce livre + 0 exemplaire", false, 2, true, 0,
                        409, "LOAN_LIMIT_REACHED"),
                Arguments.of("aucun retard + 2 actifs dont ce livre + 0 exemplaire", false, 1, true, 0,
                        409, "BOOK_ALREADY_BORROWED"),
                Arguments.of("aucun retard + moins de 3 emprunts + livre non emprunté + 0 exemplaire", false, 1, false, 0,
                        409, "BOOK_NOT_AVAILABLE"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("evaluationOrder")
    void borrow_quandPlusieursReglesViolees_alorsLaPremiereDeLOrdreDetermineLeCode(String situation, boolean overdue,
            int otherActiveLoans, boolean alreadyBorrowed, int stock, int status, String code) throws Exception {
        // Given
        Member member = newMember();
        UUID target = givenBook("Cible", 2, stock);
        for (int i = 0; i < otherActiveLoans; i++) {
            givenActiveLoan(member, givenBook("Autre " + i, 1, 0), overdue && i == 0 ? -1 : 7);
        }
        if (alreadyBorrowed) {
            givenActiveLoan(member, target, 7);
        }
        int loansBefore = loanCount();
        // When / Then : RM-LOAN-06
        borrow(member, target)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
        assertThat(loanCount()).isEqualTo(loansBefore);
        assertThat(available(target)).isEqualTo(stock);
    }

    @Test
    void borrow_quandLivreInconnuEtMembreEnRetard_alors404BookNotFoundEnPremier() throws Exception {
        // Given
        Member member = newMember();
        givenActiveLoan(member, givenBook("En retard", 1, 0), -1);
        // When / Then : ligne 1 de l'ordre d'évaluation
        borrowRaw(member.bearer(), "{\"bookId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
    }

    @Test
    void borrow_quandLivreInconnu_alors404BookNotFound() throws Exception {
        // Given / When / Then
        borrowRaw(newMember().bearer(), "{\"bookId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
        assertThat(loanCount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void borrow_quandCorpsInvalide_alors400ValidationFailedAvecErreurParChamp(String body, String code)
            throws Exception {
        // When / Then
        borrowRaw(newMember().bearer(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("bookId"))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("{}", "REQUIRED"),
                Arguments.of("{\"bookId\": \"abc\"}", "INVALID_FORMAT"),
                Arguments.of("{\"bookId\": null}", "REQUIRED"));
    }

    @Test
    void borrow_quandJsonIllisible_alors400SansErreurParChamp() throws Exception {
        // When / Then
        borrowRaw(newMember().bearer(), "{\"bookId\":")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", empty()));
    }

    @Test
    void borrow_quandCorpsContientUnMemberIdDUnAutre_alorsEmpruntCreePourLeSubDuJeton() throws Exception {
        // Given
        Member member = newMember();
        UUID victim = UUID.randomUUID();
        UUID book = givenBook("Livre", 1, 1);
        // When
        borrowRaw(member.bearer(), "{\"bookId\":\"" + book + "\",\"memberId\":\"" + victim + "\"}")
                .andExpect(status().isCreated());
        // Then
        assertThat(jdbc.queryForObject("SELECT member_id FROM loan", UUID.class)).isEqualTo(member.id());
    }

    // ------------------------------------------------------------------ consulter

    @Test
    void list_quandDeuxActifsUnRenduEtUnAutreMembre_alorsDeuxElementsTriesParEcheanceSansLesAutres() throws Exception {
        // Given
        Member member = newMember();
        Member other = newMember();
        UUID late = givenBook("Tard", 1, 0);
        UUID early = givenBook("Tôt", 1, 0);
        UUID returned = givenBook("Rendu", 1, 1);
        givenActiveLoan(member, late, 10);
        givenActiveLoan(member, early, 3);
        givenReturnedLoan(member, returned);
        givenActiveLoan(other, givenBook("Autre membre", 1, 0), 1);
        // When / Then : RM-LOAN-14
        list(member)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].title", contains("Tôt", "Tard")))
                .andExpect(jsonPath("$[0].bookId").value(early.toString()))
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].isbn").isNotEmpty())
                .andExpect(jsonPath("$[0].author").value("Auteur"))
                .andExpect(jsonPath("$[0].borrowedOn").value("2026-11-13"))
                .andExpect(jsonPath("$[0].dueOn").value("2026-11-27"))
                .andExpect(jsonPath("$[0].overdue").value(false));
    }

    @Test
    void list_quandAucunEmprunt_alors200TableauVide() throws Exception {
        // When / Then
        list(newMember()).andExpect(status().isOk()).andExpect(content().json("[]", true));
    }

    @Test
    void list_quandEmpruntEnRetard_alorsOverdueVrai() throws Exception {
        // Given : échéance hier ; un autre dont l'échéance est aujourd'hui
        Member member = newMember();
        givenActiveLoan(member, givenBook("Hier", 1, 0), -1);
        givenActiveLoan(member, givenBook("Aujourd'hui", 1, 0), 0);
        // When / Then : RM-LOAN-05
        list(member)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Hier"))
                .andExpect(jsonPath("$[0].overdue").value(true))
                .andExpect(jsonPath("$[1].title").value("Aujourd'hui"))
                .andExpect(jsonPath("$[1].overdue").value(false));
    }

    @Test
    void list_quandLivreSupprimeDuCatalogue_alorsEmpruntListeAvecChampsDuLivreNuls() throws Exception {
        // Given : un emprunt actif sur un livre qui n'existe plus (aucune clé étrangère entre modules)
        Member member = newMember();
        UUID vanished = UUID.randomUUID();
        givenActiveLoan(member, vanished, 5);
        // When / Then : RM-LOAN-32
        list(member)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].bookId").value(vanished.toString()))
                .andExpect(jsonPath("$[0].isbn").value(nullValue()))
                .andExpect(jsonPath("$[0].title").value(nullValue()))
                .andExpect(jsonPath("$[0].author").value(nullValue()));
    }

    @Test
    void list_quandTroisEmprunts_alorsDeuxRequetesSqlSeulementDontUneSeuleSurLesLivres() throws Exception {
        // Given : trois emprunts actifs de trois livres
        Member member = newMember();
        for (int i = 0; i < 3; i++) {
            givenActiveLoan(member, givenBook("Livre " + i, 1, 0), 3 + i);
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        // When
        list(member).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)));
        // Then : RM-LOAN-32, une requête sur les emprunts + une seule requête WHERE id IN (...) sur les livres
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void list_quandAucunEmprunt_alorsUneSeuleRequeteSqlEtAucuneLectureDuCatalogue() throws Exception {
        // Given
        Member member = newMember();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        // When
        list(member).andExpect(status().isOk());
        // Then : RM-LOAN-32, seule la lecture des emprunts a lieu
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ rendre

    @Test
    void giveBack_quandEmpruntActifSurLivreEpuise_alors204DateDuJourEtLivreRedisponible() throws Exception {
        // Given
        Member member = newMember();
        UUID book = givenBook("Épuisé", 1, 0);
        UUID loan = givenActiveLoan(member, book, 7);
        // When
        giveBack(member, loan).andExpect(status().isNoContent()).andExpect(content().string(""));
        // Then
        assertThat(jdbc.queryForObject("SELECT to_char(returned_on, 'YYYY-MM-DD') FROM loan WHERE id = ?",
                String.class, loan)).isEqualTo("2026-11-24");
        assertThat(available(book)).isEqualTo(1);
    }

    @Test
    void giveBack_quandFuseauAsiaTokyo_alorsDateDeRetourDansLeFuseauDeLaBibliotheque() throws Exception {
        // Given : 2026-11-24T20:00Z = 2026-11-25 à Tokyo
        clock.set(Instant.parse("2026-11-24T20:00:00Z"), ZoneId.of("Asia/Tokyo"));
        Member member = newMember();
        UUID loan = givenActiveLoan(member, givenBook("Livre", 1, 0), 7);
        // When
        giveBack(member, loan).andExpect(status().isNoContent());
        // Then
        assertThat(jdbc.queryForObject("SELECT to_char(returned_on, 'YYYY-MM-DD') FROM loan WHERE id = ?",
                String.class, loan)).isEqualTo("2026-11-25");
    }

    @Test
    void giveBack_quandDoubleRetour_alors409LoanAlreadyReturnedSansSecondIncrement() throws Exception {
        // Given : un emprunt déjà rendu, le livre a 1 exemplaire disponible sur 2
        Member member = newMember();
        UUID book = givenBook("Livre", 2, 1);
        UUID loan = givenActiveLoan(member, book, 7);
        giveBack(member, loan).andExpect(status().isNoContent());
        assertThat(available(book)).isEqualTo(2);
        // When / Then
        giveBack(member, loan)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_ALREADY_RETURNED"));
        assertThat(available(book)).isEqualTo(2);
    }

    @Test
    void giveBack_quandEmpruntDUnAutreMembre_alors404LoanNotFoundEtRienModifie() throws Exception {
        // Given
        Member owner = newMember();
        Member intruder = newMember();
        UUID book = givenBook("Livre", 1, 0);
        UUID loan = givenActiveLoan(owner, book, 7);
        // When / Then : RM-LOAN-13
        giveBack(intruder, loan)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"));
        assertThat(available(book)).isZero();
        assertThat(activeLoanCount(owner)).isEqualTo(1);
    }

    @Test
    void giveBack_quandIdentifiantInconnuOuMalForme_alors404LoanNotFound() throws Exception {
        // Given
        Member member = newMember();
        // When / Then
        giveBack(member, UUID.randomUUID())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"));
        giveBack(member, "abc")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"));
    }

    @Test
    void giveBack_quandEmpruntEnRetard_alors204() throws Exception {
        // Given
        Member member = newMember();
        UUID loan = givenActiveLoan(member, givenBook("En retard", 1, 0), -5);
        // When / Then
        giveBack(member, loan).andExpect(status().isNoContent());
    }

    @Test
    void giveBack_quandLivreSupprimeDuCatalogue_alors204StockNonModifie() throws Exception {
        // Given : D3, le livre n'existe plus
        Member member = newMember();
        UUID loan = givenActiveLoan(member, UUID.randomUUID(), 7);
        // When / Then
        giveBack(member, loan).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT returned_on IS NOT NULL FROM loan WHERE id = ?", Boolean.class, loan))
                .isTrue();
    }

    @Test
    void giveBack_quandRestitutionAuDessusDuTotal_alorsErreurTechniqueEtRetourAnnule() throws Exception {
        // Given : incohérence de données (livre complet alors qu'un emprunt est actif), D3
        Member member = newMember();
        UUID book = givenBook("Incohérent", 1, 1);
        UUID loan = givenActiveLoan(member, book, 7);
        // When / Then : erreur technique (500), la transaction est annulée : emprunt toujours actif
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> giveBack(member, loan))
                .hasRootCauseInstanceOf(com.example.library.catalog.domain.exception.AvailableCopiesOverflowException.class);
        assertThat(jdbc.queryForObject("SELECT returned_on IS NULL FROM loan WHERE id = ?", Boolean.class, loan))
                .isTrue();
        assertThat(available(book)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ sécurité

    @Test
    void endpoints_quandNonAuthentifie_alors401() throws Exception {
        // When / Then
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/" + UUID.randomUUID() + "/return")).andExpect(status().isUnauthorized());
    }

    @Test
    void endpoints_quandLibrarianSeul_alors403() throws Exception {
        // Given
        String librarian = TestJwts.bearer(TestJwts.librarian());
        UUID book = givenBook("Livre", 1, 1);
        // When / Then
        mockMvc.perform(post(URL).header("Authorization", librarian).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + book + "\"}")).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).header("Authorization", librarian)).andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/" + UUID.randomUUID() + "/return").header("Authorization", librarian))
                .andExpect(status().isForbidden());
        assertThat(loanCount()).isZero();
        assertThat(available(book)).isEqualTo(1);
    }

    @Test
    void borrow_quandLibrarianEtMember_alors201PourLuiMeme() throws Exception {
        // Given
        UUID id = UUID.randomUUID();
        String both = TestJwts.bearer(TestJwts.withRoles(id.toString(), "bib@example.org", "LIBRARIAN", "MEMBER"));
        UUID book = givenBook("Livre", 1, 1);
        // When / Then
        borrowRaw(both, "{\"bookId\":\"" + book + "\"}").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT member_id FROM loan", UUID.class)).isEqualTo(id);
    }

    @Test
    void borrow_quandCookieSansCsrf_alors403SansEffetEnBase() throws Exception {
        // Given
        Member member = newMember();
        UUID book = givenBook("Livre", 1, 1);
        String token = TestJwts.withRoles(member.id().toString(), "membre@example.org", "MEMBER");
        // When / Then
        mockMvc.perform(post(URL).cookie(new Cookie("ACCESS_TOKEN", token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + book + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(loanCount()).isZero();
    }

    @Test
    void borrow_quandCookieAvecCsrf_alors201() throws Exception {
        // Given
        Member member = newMember();
        UUID book = givenBook("Livre", 1, 1);
        String token = TestJwts.withRoles(member.id().toString(), "membre@example.org", "MEMBER");
        // When / Then
        mockMvc.perform(post(URL).cookie(new Cookie("ACCESS_TOKEN", token), new Cookie("XSRF-TOKEN", "abc"))
                        .header("X-XSRF-TOKEN", "abc")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bookId\":\"" + book + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void endpoints_quandSubNonUuid_alors401UnauthenticatedSansAucuneModification() throws Exception {
        // Given
        String authorization = TestJwts.bearer(TestJwts.withRoles("not-a-uuid", "membre@example.org", "MEMBER"));
        UUID book = givenBook("Livre", 1, 1);
        // When / Then
        borrowRaw(authorization, "{\"bookId\":\"" + book + "\"}")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get(URL).header("Authorization", authorization))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post(URL + "/" + UUID.randomUUID() + "/return").header("Authorization", authorization))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(loanCount()).isZero();
        assertThat(available(book)).isEqualTo(1);
    }

    @Test
    void borrow_quandStockEtCompteursApresEmprunt_alorsAutresLivresEtTotalInchanges() throws Exception {
        // Given : RM-LOAN-09
        Member member = newMember();
        UUID target = givenBook("Cible", 3, 3);
        UUID untouched = givenBook("Intact", 4, 2);
        // When
        borrow(member, target).andExpect(status().isCreated());
        // Then
        assertThat(jdbc.queryForObject("SELECT total_copies FROM book WHERE id = ?", Integer.class, target)).isEqualTo(3);
        assertThat(available(untouched)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT total_copies FROM book WHERE id = ?", Integer.class, untouched))
                .isEqualTo(4);
    }

    @Test
    void borrow_quandEmpruntsRendus_alorsConservesEnBaseSansSuppression() throws Exception {
        // Given : RM-LOAN-15
        Member member = newMember();
        UUID book = givenBook("Livre", 1, 1);
        UUID loan = borrowAndGetLoanId(member, book);
        // When
        giveBack(member, loan).andExpect(status().isNoContent());
        // Then
        assertThat(loanCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT returned_on IS NOT NULL FROM loan WHERE id = ?", Boolean.class, loan))
                .isTrue();
    }

    @Test
    void location_quandEmpruntCree_alorsSeTermineParLIdentifiantDeLEmprunt() throws Exception {
        // Given
        Member member = newMember();
        UUID book = givenBook("Livre", 1, 1);
        // When
        var result = borrow(member, book).andExpect(status().isCreated()).andReturn();
        // Then
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(result.getResponse().getHeader("Location")).endsWith("/api/v1/loans/" + id);
    }

    // ------------------------------------------------------------------ 503 LOAN_BUSY : verrous réellement tenus

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** Namespace du verrou consultatif de loan (0x4C4F414E, « LOAN »), voir LoanPersistenceAdapter. */
    private static final int ADVISORY_NAMESPACE = 0x4C4F414E;

    /** Tient un verrou dans une transaction ouverte sur un autre thread, jusqu'à ce que le test le libère. */
    private java.util.concurrent.CompletableFuture<Void> holdLock(java.util.concurrent.CountDownLatch locked,
            java.util.concurrent.CountDownLatch release, String lockSql, Object... args) {
        return java.util.concurrent.CompletableFuture.runAsync(() ->
                new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            jdbc.queryForList(lockSql, args);
                            locked.countDown();
                            try {
                                release.await(60, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }));
    }

    @Test
    void borrow_quandVerrouDuMembreTenuParUneAutreTransaction_alors503LoanBusyApresLeDelaiPuisRejouable()
            throws Exception {
        // Given : une autre transaction tient le verrou consultatif de ce membre (délai de verrou de loan : 5 s)
        Member member = newMember();
        UUID book = givenBook("Livre", 2, 2);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holder = holdLock(locked, release,
                "SELECT pg_advisory_xact_lock(?, hashtext(CAST(? AS text)))", ADVISORY_NAMESPACE, member.id().toString());
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            // When
            long start = System.nanoTime();
            borrow(member, book)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("LOAN_BUSY"))
                    .andExpect(jsonPath("$.status").value(503));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            // Then : la requête a bien attendu le délai de verrou, et rien n'a été modifié
            assertThat(elapsedMs).isBetween(4_000L, 15_000L);
            assertThat(loanCount()).isZero();
            assertThat(available(book)).isEqualTo(2);
        } finally {
            release.countDown();
            holder.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        // Then : verrou libéré, la même requête réussit
        borrow(member, book).andExpect(status().isCreated());
    }

    @Test
    void giveBack_quandLigneDeLEmpruntVerrouilleeParUneAutreTransaction_alors503LoanBusyEtRienModifie()
            throws Exception {
        // Given : une autre transaction tient SELECT ... FOR UPDATE sur la ligne de l'emprunt
        Member member = newMember();
        UUID book = givenBook("Livre", 1, 0);
        UUID loan = givenActiveLoan(member, book, 7);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holder = holdLock(locked, release, "SELECT id FROM loan WHERE id = ? FOR UPDATE", loan);
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            // When / Then
            giveBack(member, loan)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("LOAN_BUSY"));
            assertThat(available(book)).isZero();
            assertThat(activeLoanCount(member)).isEqualTo(1);
        } finally {
            release.countDown();
            holder.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        // Then : verrou libéré, le retour réussit
        giveBack(member, loan).andExpect(status().isNoContent());
        assertThat(available(book)).isEqualTo(1);
    }
}
