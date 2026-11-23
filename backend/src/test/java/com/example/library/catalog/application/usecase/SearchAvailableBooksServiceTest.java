package com.example.library.catalog.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.example.library.catalog.application.fake.InMemoryBookRepository;
import com.example.library.catalog.application.port.in.SearchAvailableBooksQuery;
import com.example.library.catalog.domain.exception.InvalidCatalogSearchException;
import com.example.library.catalog.domain.model.AvailableBooksPage;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import com.example.library.shared.UseCase;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

/** RM-04, RM-CAT-14, 15, 16, 17, 18, 19, 20, 22 : orchestration de la consultation paginée du catalogue. */
class SearchAvailableBooksServiceTest {

    private final InMemoryBookRepository repository = new InMemoryBookRepository();
    private final SearchAvailableBooksService service = new SearchAvailableBooksService(repository, repository);

    private void givenBook(String isbn, String title, String author, int total, int available) {
        repository.givenExisting(Book.restore(BookId.newId(), Isbn.of(isbn), title, author, total, available));
    }

    private void givenAvailableBooks(int count) {
        for (int i = 0; i < count; i++) {
            givenBook("978000000%04d".formatted(i), "Livre %02d".formatted(i), "Auteur", 1, 1);
        }
    }

    private AvailableBooksPage search(Integer page, Integer size, String q) {
        return service.search(SearchAvailableBooksQuery.of(page, size, q));
    }

    @Test
    void search_quandVingtCinqLivresEtParametresParDefaut_alorsPremierePageDeDixEtTotaux() {
        // Given
        givenAvailableBooks(25);
        // When
        AvailableBooksPage result = search(null, null, null);
        // Then
        assertThat(result.content()).hasSize(10);
        assertThat(result.content()).extracting(Book::getTitle).first().isEqualTo("Livre 00");
        assertThat(result.pageRequest().page()).isZero();
        assertThat(result.pageRequest().size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(25);
        assertThat(result.totalPages()).isEqualTo(3);
    }

    @Test
    void search_quandDerniereePage_alorsCinqLivresDeRangVingtEtUnAVingtCinq() {
        // Given
        givenAvailableBooks(25);
        // When
        AvailableBooksPage result = search(2, 10, null);
        // Then
        assertThat(result.content()).extracting(Book::getTitle)
                .containsExactly("Livre 20", "Livre 21", "Livre 22", "Livre 23", "Livre 24");
    }

    @Test
    void search_quandPagesSuccessives_alorsSansChevauchementNiPerte() {
        // Given
        givenAvailableBooks(25);
        // When
        List<String> all = new java.util.ArrayList<>();
        for (int page = 0; page < 3; page++) {
            search(page, 10, null).content().forEach(book -> all.add(book.getTitle()));
        }
        // Then : RM-CAT-16
        assertThat(all).hasSize(25).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void search_quandPageAuDelaDeLaDerniere_alorsContenuVideEtTotauxCorrects() {
        // Given
        givenAvailableBooks(25);
        // When
        AvailableBooksPage result = search(9, 10, null);
        // Then : RM-CAT-19, la page n'est pas lue
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(25);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(repository.loadPageCalls()).isZero();
    }

    @Test
    void search_quandOffsetEgalAuTotal_alorsContenuVide() {
        // Given
        givenAvailableBooks(20);
        // When
        AvailableBooksPage result = search(2, 10, null);
        // Then : offset 20 = total 20
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(20);
    }

    @Test
    void search_quandPageAuDelaAvecGrandNombre_alorsAucunDebordement() {
        // Given
        givenAvailableBooks(3);
        // When
        AvailableBooksPage result = search(Integer.MAX_VALUE, 50, null);
        // Then
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(3);
    }

    @Test
    void search_quandLivreSansExemplaireDisponible_alorsExcluDuContenuEtDuTotal() {
        // Given : RM-04, RM-CAT-14
        givenBook("9782070368228", "Epuise", "A", 2, 0);
        givenBook("9782070368235", "Alpha", "A", 1, 1);
        givenBook("9782070368242", "Beta", "A", 3, 2);
        // When
        AvailableBooksPage result = search(null, null, null);
        // Then
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.content()).extracting(Book::getTitle).containsExactly("Alpha", "Beta");
    }

    @Test
    void search_quandAucunLivreDisponible_alorsPageVideEtZeroPage() {
        // Given
        givenBook("9782070368228", "Epuise", "A", 2, 0);
        // When
        AvailableBooksPage result = search(null, null, null);
        // Then
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    void search_quandCatalogueVide_alorsPageLueJamaisAppelee() {
        // Given / When
        search(null, null, null);
        // Then : seul le comptage est lu
        assertThat(repository.countCalls()).isEqualTo(1);
        assertThat(repository.loadPageCalls()).isZero();
    }

    @Test
    void search_quandTriSansCasseEtIsbnEnDepartage_alorsOrdreDuCatalogue() {
        // Given : RM-CAT-16
        givenBook("9782070368242", "bravo", "A", 1, 1);
        givenBook("9782070368235", "Bravo", "B", 1, 1);
        givenBook("9782070368228", "alpha", "C", 1, 1);
        givenBook("9782070368259", "Zulu", "D", 1, 1);
        // When
        AvailableBooksPage result = search(null, null, null);
        // Then
        assertThat(result.content()).extracting(book -> book.getIsbn().value())
                .containsExactly("9782070368228", "9782070368235", "9782070368242", "9782070368259");
    }

    private void givenCamusZolaCatalogue() {
        givenBook("9782070368228", "L'Étranger", "Albert Camus", 2, 2);
        givenBook("9782070368235", "La Peste", "Albert Camus", 2, 1);
        givenBook("9782070368242", "Germinal", "Émile Zola", 2, 2);
    }

    @Test
    void search_quandRechercheParAuteurSansCasse_alorsLivresCorrespondantsEtTotalFiltre() {
        // Given
        givenCamusZolaCatalogue();
        // When
        AvailableBooksPage lower = search(null, null, "camus");
        AvailableBooksPage upper = search(null, null, "CAMUS");
        // Then
        assertThat(lower.content()).extracting(Book::getTitle).containsExactly("L'Étranger", "La Peste");
        assertThat(lower.totalElements()).isEqualTo(2);
        assertThat(upper.content()).isEqualTo(lower.content());
    }

    @Test
    void search_quandRechercheParTitrePartiel_alorsUnSeulLivre() {
        // Given
        givenCamusZolaCatalogue();
        // When / Then
        assertThat(search(null, null, "peste").content()).extracting(Book::getTitle).containsExactly("La Peste");
        assertThat(search(null, null, "germ").content()).extracting(Book::getTitle).containsExactly("Germinal");
        assertThat(search(null, null, "ZOLA").content()).extracting(Book::getTitle).containsExactly("Germinal");
    }

    @Test
    void search_quandRechercheSansResultat_alorsPageVideEtTotalZero() {
        // Given
        givenCamusZolaCatalogue();
        // When
        AvailableBooksPage result = search(null, null, "introuvab");
        // Then : RM-CAT-18
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    void search_quandTexteVideOuBlanc_alorsTousLesLivres() {
        // Given
        givenCamusZolaCatalogue();
        // When / Then
        assertThat(search(null, null, "").totalElements()).isEqualTo(3);
        assertThat(search(null, null, "   ").totalElements()).isEqualTo(3);
        assertThat(search(null, null, null).totalElements()).isEqualTo(3);
    }

    @Test
    void search_quandTexteAvecEspacesAutour_alorsRogneAvantRecherche() {
        // Given
        givenCamusZolaCatalogue();
        // When
        AvailableBooksPage result = search(null, null, "  zola  ");
        // Then
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(repository.lastText().value()).isEqualTo("zola");
    }

    @Test
    void search_quandRechercheSurLIsbn_alorsAucunResultat() {
        // Given
        givenCamusZolaCatalogue();
        // When / Then
        assertThat(search(null, null, "9782070368228").totalElements()).isZero();
    }

    @Test
    void search_quandLivreEpuiseCorrespondALaRecherche_alorsExclu() {
        // Given
        givenBook("9782070368228", "Hugo epuise", "Victor Hugo", 1, 0);
        givenBook("9782070368235", "Les Miserables", "Victor Hugo", 1, 1);
        // When
        AvailableBooksPage result = search(null, null, "hugo");
        // Then
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content()).extracting(Book::getTitle).containsExactly("Les Miserables");
    }

    @Test
    void search_quandRechercheEtPaginationCombinees_alorsFiltreAvantPagination() {
        // Given : 15 livres de Hugo parmi 30
        for (int i = 0; i < 15; i++) {
            givenBook("97800000%05d".formatted(i), "Hugo %02d".formatted(i), "Victor Hugo", 1, 1);
        }
        for (int i = 15; i < 30; i++) {
            givenBook("97800000%05d".formatted(i), "Autre %02d".formatted(i), "Quelqu'un", 1, 1);
        }
        // When
        AvailableBooksPage result = search(1, 10, "hugo");
        // Then : RM-CAT-18
        assertThat(result.content()).hasSize(5);
        assertThat(result.totalElements()).isEqualTo(15);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    @Test
    void search_quandTransmissionAuPort_alorsTexteEtPageDeLaRequete() {
        // Given
        givenAvailableBooks(30);
        // When
        search(1, 20, "livre");
        // Then
        assertThat(repository.lastPageRequest().page()).isEqualTo(1);
        assertThat(repository.lastPageRequest().size()).isEqualTo(20);
        assertThat(repository.lastText().value()).isEqualTo("livre");
    }

    @Test
    void search_quandServiceAppele_alorsAucuneEcritureSurLeCatalogue() {
        // Given : RM-CAT-22
        givenAvailableBooks(5);
        List<Book> before = repository.all();
        // When
        search(null, null, "livre");
        // Then
        assertThat(repository.all()).isEqualTo(before);
    }

    @Test
    void query_quandParametresInvalides_alorsInvalidCatalogSearchException() {
        // Given / When / Then : RM-CAT-15, RM-CAT-17
        assertThatThrownBy(() -> SearchAvailableBooksQuery.of(-1, null, null))
                .isInstanceOf(InvalidCatalogSearchException.class);
        assertThatThrownBy(() -> SearchAvailableBooksQuery.of(null, 0, null))
                .isInstanceOf(InvalidCatalogSearchException.class);
        assertThatThrownBy(() -> SearchAvailableBooksQuery.of(null, 51, null))
                .isInstanceOf(InvalidCatalogSearchException.class);
        assertThatThrownBy(() -> SearchAvailableBooksQuery.of(null, null, "x".repeat(101)))
                .isInstanceOf(InvalidCatalogSearchException.class);
    }

    @Test
    void query_quandRechercheNulle_alorsNullPointerException() {
        // Given / When / Then
        assertThatThrownBy(() -> new SearchAvailableBooksQuery(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void service_quandInspecte_alorsUseCaseTransactionnelEnLectureSeule() {
        // Given / When
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(
                SearchAvailableBooksService.class, Transactional.class);
        // Then : RM-CAT-22
        assertThat(SearchAvailableBooksService.class).hasAnnotation(UseCase.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }
}
