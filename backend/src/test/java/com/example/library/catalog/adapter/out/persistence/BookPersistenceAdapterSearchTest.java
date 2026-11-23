package com.example.library.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.CatalogPageRequest;
import com.example.library.catalog.domain.model.Isbn;
import com.example.library.catalog.domain.model.SearchText;
import com.example.library.catalog.domain.service.AvailableBooksSelection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistance réelle (PostgreSQL via Testcontainers) de la consultation paginée :
 * RM-04, RM-CAT-14, 16, 17, 18, 19, 22. Le résultat de la base est comparé à la sémantique de référence du domaine
 * ({@link SearchText#matches(Book)} et {@link AvailableBooksSelection#CATALOG_ORDER}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestcontainersConfiguration.class, BookPersistenceAdapter.class, BookPersistenceMapper.class})
class BookPersistenceAdapterSearchTest {

    @Autowired
    private BookPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    private int sequence;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM book");
    }

    private String nextIsbn() {
        return "978100000%04d".formatted(sequence++);
    }

    private void givenBook(String title, String author, int total, int available) {
        adapter.save(Book.restore(BookId.newId(), Isbn.of(nextIsbn()), title, author, total, available));
    }

    private void givenBookWithIsbn(String isbn, String title, String author) {
        adapter.save(Book.restore(BookId.newId(), Isbn.of(isbn), title, author, 1, 1));
    }

    private List<Book> page(String q, int page, int size) {
        return adapter.loadAvailableBooksPage(SearchText.of(q), new CatalogPageRequest(page, size));
    }

    private long count(String q) {
        return adapter.countAvailableBooks(SearchText.of(q));
    }

    private static List<String> titles(List<Book> books) {
        return books.stream().map(Book::getTitle).toList();
    }

    private void givenCamusZola() {
        givenBook("L'Étranger", "Albert Camus", 2, 2);
        givenBook("La Peste", "Albert Camus", 2, 1);
        givenBook("Germinal", "Émile Zola", 2, 2);
    }

    // ---- Pagination et tri (RM-CAT-16, RM-CAT-19)

    @Test
    void loadAvailableBooksPage_quandVingtCinqLivres_alorsPagesDisjointesEtCompletesDansLOrdreDuCatalogue() {
        // Given
        for (int i = 24; i >= 0; i--) {
            givenBook("Livre %02d".formatted(i), "Auteur", 1, 1);
        }
        // When
        List<Book> first = page(null, 0, 10);
        List<Book> second = page(null, 1, 10);
        List<Book> third = page(null, 2, 10);
        // Then
        assertThat(first).hasSize(10);
        assertThat(second).hasSize(10);
        assertThat(third).hasSize(5);
        List<Book> all = new ArrayList<>(first);
        all.addAll(second);
        all.addAll(third);
        assertThat(all).extracting(book -> book.getId().value()).doesNotHaveDuplicates();
        assertThat(titles(all)).isSortedAccordingTo(String::compareTo).hasSize(25);
        assertThat(titles(third)).containsExactly("Livre 20", "Livre 21", "Livre 22", "Livre 23", "Livre 24");
        assertThat(count(null)).isEqualTo(25);
    }

    @Test
    void loadAvailableBooksPage_quandPageAuDelaDuTotal_alorsListeVide() {
        // Given
        givenBook("A", "X", 1, 1);
        // When / Then
        assertThat(page(null, 9, 10)).isEmpty();
        assertThat(page("a", 9, 10)).isEmpty();
    }

    @Test
    void loadAvailableBooksPage_quandPageTresGrande_alorsOffsetEnLongSansDebordement() {
        // Given
        givenBook("A", "X", 1, 1);
        // When / Then : 2 000 000 000 * 50 dépasse Integer.MAX_VALUE
        assertThat(page(null, 2_000_000_000, 50)).isEmpty();
    }

    @Test
    void loadAvailableBooksPage_quandCasseMixteAccentsEtIsbnEnDepartage_alorsOrdreIdentiqueAuDomaine() {
        // Given
        givenBookWithIsbn("9782070368228", "banane", "A");
        givenBookWithIsbn("9782070368235", "Abricot", "A");
        givenBookWithIsbn("9782070368242", "zèbre", "A");
        givenBookWithIsbn("9782070368259", "Éclair", "A");
        givenBookWithIsbn("9782070368266", "apple", "A");
        givenBookWithIsbn("9782070368273", "ZEBU", "A");
        givenBookWithIsbn("9782070368280", "Ça va", "A");
        givenBookWithIsbn("9782070368297", "Roman", "A");
        givenBookWithIsbn("9782070368303", "ROMAN", "A");
        // When
        List<Book> result = page(null, 0, 50);
        // Then
        List<Book> expected = result.stream().sorted(AvailableBooksSelection.CATALOG_ORDER).toList();
        assertThat(result).hasSize(9);
        assertThat(result).extracting(book -> book.getIsbn().value())
                .containsExactlyElementsOf(expected.stream().map(book -> book.getIsbn().value()).toList());
    }

    @Test
    void loadAvailableBooksPage_quandPageSansRecherche_alorsMemeContenuQueLaTeteDeListeDeLaMethodeHistorique() {
        // Given
        for (int i = 0; i < 12; i++) {
            givenBook("Livre %02d".formatted(i), "Auteur", 1, 1);
        }
        // When / Then : identité requise par le cache de tête de liste (RM-CAT-24)
        assertThat(page(null, 0, 10)).extracting(book -> book.getId().value())
                .containsExactlyElementsOf(adapter.loadAvailableBooks(10).stream()
                        .map(book -> book.getId().value()).toList());
    }

    // ---- Disponibilité (RM-04, RM-CAT-14)

    @Test
    void loadAvailableBooksPage_quandLivreSansExemplaireDisponible_alorsExcluDeLaPageEtDuTotal() {
        // Given
        givenBook("Epuise", "A", 2, 0);
        givenBook("Alpha", "A", 2, 1);
        givenBook("Beta", "A", 2, 2);
        // When / Then
        assertThat(titles(page(null, 0, 10))).containsExactly("Alpha", "Beta");
        assertThat(count(null)).isEqualTo(2);
    }

    @Test
    void loadAvailableBooksPage_quandLivreEpuiseCorrespondALaRecherche_alorsExclu() {
        // Given
        givenBook("Hugo epuise", "Victor Hugo", 1, 0);
        givenBook("Les Miserables", "Victor Hugo", 1, 1);
        // When / Then
        assertThat(titles(page("hugo", 0, 10))).containsExactly("Les Miserables");
        assertThat(count("hugo")).isEqualTo(1);
    }

    @Test
    void countAvailableBooks_quandAucunLivreDisponible_alorsZero() {
        // Given
        givenBook("Epuise", "A", 2, 0);
        // When / Then
        assertThat(count(null)).isZero();
        assertThat(page(null, 0, 10)).isEmpty();
    }

    @Test
    void countAvailableBooks_quandCatalogueVide_alorsZero() {
        // Given / When / Then
        assertThat(count(null)).isZero();
        assertThat(count("camus")).isZero();
    }

    // ---- Recherche (RM-CAT-17, RM-CAT-18)

    @Test
    void search_quandAuteurSansCasse_alorsLivresCorrespondantsEtTotalFiltre() {
        // Given
        givenCamusZola();
        // When / Then
        assertThat(titles(page("camus", 0, 10))).containsExactly("L'Étranger", "La Peste");
        assertThat(titles(page("CAMUS", 0, 10))).containsExactly("L'Étranger", "La Peste");
        assertThat(count("camus")).isEqualTo(2);
        assertThat(count("CAMUS")).isEqualTo(2);
    }

    @Test
    void search_quandSousChaineDuTitreOuDeLAuteur_alorsCorrespondances() {
        // Given
        givenCamusZola();
        // When / Then
        assertThat(titles(page("peste", 0, 10))).containsExactly("La Peste");
        assertThat(titles(page("germ", 0, 10))).containsExactly("Germinal");
        assertThat(titles(page("ZOLA", 0, 10))).containsExactly("Germinal");
        assertThat(count("peste")).isEqualTo(1);
    }

    @Test
    void search_quandAucunResultat_alorsListeVideEtTotalZero() {
        // Given
        givenCamusZola();
        // When / Then
        assertThat(page("introuvab", 0, 10)).isEmpty();
        assertThat(count("introuvab")).isZero();
    }

    @Test
    void search_quandVideOuBlanc_alorsTousLesLivres() {
        // Given
        givenCamusZola();
        // When / Then
        assertThat(count("")).isEqualTo(3);
        assertThat(count("   ")).isEqualTo(3);
        assertThat(page("   ", 0, 10)).hasSize(3);
    }

    @Test
    void search_quandTexteAccentueEnMinuscule_alorsTrouveLeTitreMajuscule() {
        // Given : insensibilité à la casse, y compris pour une lettre accentuée
        givenCamusZola();
        // When / Then
        assertThat(titles(page("étranger", 0, 10))).containsExactly("L'Étranger");
        assertThat(titles(page("ÉMILE", 0, 10))).containsExactly("Germinal");
    }

    @Test
    void search_quandTexteSansAccent_alorsNeTrouvePasLeTitreAccentue() {
        // Given : recherche sensible aux accents (décision Q5)
        givenCamusZola();
        // When / Then
        assertThat(page("etranger", 0, 10)).isEmpty();
        assertThat(count("etranger")).isZero();
        assertThat(count("emile")).isZero();
    }

    @Test
    void search_quandTexteEstUnIsbn_alorsAucunResultat() {
        // Given : l'ISBN n'est pas cherché
        givenBookWithIsbn("9782070368228", "Titre", "Auteur");
        // When / Then
        assertThat(count("9782070368228")).isZero();
        assertThat(count("97820703")).isZero();
    }

    @Test
    void search_quandApostropheDansLeTexte_alorsLitterale() {
        // Given
        givenCamusZola();
        // When / Then
        assertThat(titles(page("l'é", 0, 10))).containsExactly("L'Étranger");
    }

    @Test
    void search_quandInjectionSqlDansLeTexte_alorsTraiteCommeTexte() {
        // Given
        givenCamusZola();
        // When / Then : requête paramétrée
        assertThat(count("'; DROP TABLE book; --")).isZero();
        assertThat(count(null)).isEqualTo(3);
    }

    @Test
    void search_quandRechercheEtPaginationCombinees_alorsFiltreAvantPagination() {
        // Given : 15 livres de Hugo parmi 30
        for (int i = 0; i < 15; i++) {
            givenBook("Roman %02d".formatted(i), "Victor Hugo", 1, 1);
        }
        for (int i = 15; i < 30; i++) {
            givenBook("Roman %02d".formatted(i), "Autre auteur", 1, 1);
        }
        // When / Then
        assertThat(page("hugo", 1, 10)).hasSize(5);
        assertThat(page("hugo", 0, 10)).hasSize(10);
        assertThat(page("hugo", 2, 10)).isEmpty();
        assertThat(count("hugo")).isEqualTo(15);
    }

    @Test
    void search_quandOuLogiqueTitreOuAuteur_alorsChaqueLivreRetenuUneSeuleFois() {
        // Given : "hugo" est dans le titre ET dans l'auteur
        givenBook("Hugo par Hugo", "Victor Hugo", 1, 1);
        // When / Then
        assertThat(page("hugo", 0, 10)).hasSize(1);
        assertThat(count("hugo")).isEqualTo(1);
    }

    // ---- Caractères spéciaux littéraux (RM-CAT-17, ESCAPE)

    @Test
    void search_quandPourcentLitteral_alorsSeulLeTitreContenantPourcent() {
        // Given
        givenBook("100% Python", "Auteur", 1, 1);
        givenBook("Le Python", "Auteur", 1, 1);
        // When / Then
        assertThat(titles(page("100%", 0, 10))).containsExactly("100% Python");
        assertThat(count("100%")).isEqualTo(1);
        assertThat(titles(page("%", 0, 10))).containsExactly("100% Python");
        assertThat(count("%")).isEqualTo(1);
    }

    @Test
    void search_quandSoulignementLitteral_alorsPasDeJokerUnCaractere() {
        // Given
        givenBook("snake_case", "Auteur", 1, 1);
        givenBook("snakeXcase", "Auteur", 1, 1);
        // When / Then
        assertThat(titles(page("snake_case", 0, 10))).containsExactly("snake_case");
        assertThat(titles(page("_", 0, 10))).containsExactly("snake_case");
        assertThat(count("_")).isEqualTo(1);
    }

    @Test
    void search_quandAntislashLitteral_alorsSeulLeTitreContenantAntislash() {
        // Given
        givenBook("C:\\temp", "Auteur", 1, 1);
        givenBook("Le Python", "Auteur", 1, 1);
        // When / Then
        assertThat(titles(page("\\", 0, 10))).containsExactly("C:\\temp");
        assertThat(count("\\")).isEqualTo(1);
        assertThat(titles(page("c:\\t", 0, 10))).containsExactly("C:\\temp");
    }

    @Test
    void search_quandCaracteresSpeciauxDansLAuteur_alorsTrouves() {
        // Given
        givenBook("Titre", "A_B%C\\D", 1, 1);
        givenBook("Autre", "AxByCzD", 1, 1);
        // When / Then
        assertThat(titles(page("a_b%c\\d", 0, 10))).containsExactly("Titre");
        assertThat(count("a_b%c\\d")).isEqualTo(1);
    }

    @Test
    void search_quandAntislashSuiviDeJoker_alorsToutLitteral() {
        // Given
        givenBook("a\\%b", "Auteur", 1, 1);
        givenBook("a\\Xb", "Auteur", 1, 1);
        // When / Then : "\%" saisi = deux caractères littéraux
        assertThat(titles(page("\\%", 0, 10))).containsExactly("a\\%b");
        assertThat(count("\\%")).isEqualTo(1);
    }

    // ---- Cohérence page / total et sémantique de référence

    @Test
    void search_quandJeuMixte_alorsBaseIdentiqueALaSemantiqueDeReferenceDuDomaine() {
        // Given
        givenCamusZola();
        givenBook("100% Python", "Auteur_X", 1, 1);
        givenBook("Le Python", "Auteur", 1, 1);
        givenBook("Épuisé camus", "Camus", 1, 0);
        List<Book> allBooks = page(null, 0, 50);
        // When / Then
        for (String q : List.of("camus", "CAMUS", "py", "%", "_", "é", "e", "x", "zola", "a", "1", "  ")) {
            SearchText text = SearchText.of(q);
            List<String> expected = allBooks.stream().filter(text::matches)
                    .sorted(AvailableBooksSelection.CATALOG_ORDER).map(Book::getTitle).toList();
            assertThat(titles(page(q, 0, 50))).as("page pour q=%s", q).containsExactlyElementsOf(expected);
            assertThat(count(q)).as("total pour q=%s", q).isEqualTo(expected.size());
        }
    }

    @Test
    void search_quandConsultation_alorsAucuneEcritureVersionInchangee() {
        // Given : RM-CAT-22
        givenBook("A", "X", 2, 1);
        Long before = jdbc.queryForObject("SELECT version FROM book", Long.class);
        // When
        page("a", 0, 10);
        page(null, 0, 10);
        count("a");
        count(null);
        // Then
        assertThat(jdbc.queryForObject("SELECT version FROM book", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM book", Long.class)).isEqualTo(1L);
    }
}
