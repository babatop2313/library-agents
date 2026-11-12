package com.example.library.catalog.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.library.catalog.application.fake.InMemoryBookRepository;
import com.example.library.catalog.application.port.in.ListAvailableBooksQuery;
import com.example.library.catalog.application.port.out.LoadAvailableBooksPort;
import com.example.library.catalog.domain.exception.LimitOutOfRangeException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

/** RM-04, RM-MCP-06, RM-MCP-07, RM-MCP-08, RM-MCP-13 : orchestration de la liste des livres disponibles. */
class ListAvailableBooksServiceTest {

    private final InMemoryBookRepository repository = new InMemoryBookRepository();
    private final ListAvailableBooksService service = new ListAvailableBooksService(repository);

    private void givenBook(String isbn, String title, int total, int available) {
        repository.givenExisting(Book.restore(BookId.newId(), Isbn.of(isbn), title, "Auteur", total, available));
    }

    private void givenManyAvailableBooks(int count) {
        for (int i = 0; i < count; i++) {
            givenBook("978000000%04d".formatted(i), "Livre %02d".formatted(i), 1, 1);
        }
    }

    @Test
    void listAvailable_quandCinqLivresDontTroisDisponibles_alorsTroisTries() {
        // Given
        givenBook("9782070368228", "Zorba", 2, 1);
        givenBook("9782070368235", "epuise", 2, 0);
        givenBook("9782070368242", "alpha", 1, 1);
        givenBook("9782070368259", "Epuise aussi", 1, 0);
        givenBook("9782070368266", "Beta", 4, 4);
        // When
        List<Book> result = service.listAvailable(ListAvailableBooksQuery.of(10));
        // Then
        assertThat(result).extracting(Book::getTitle).containsExactly("alpha", "Beta", "Zorba");
        assertThat(result).allMatch(b -> b.getAvailableCopies() >= 1);
    }

    @Test
    void listAvailable_quandLimiteCinq_alorsCinqLivres() {
        // Given
        givenManyAvailableBooks(30);
        // When
        List<Book> result = service.listAvailable(ListAvailableBooksQuery.of(5));
        // Then
        assertThat(result).hasSize(5);
        assertThat(repository.lastMaxResults()).isEqualTo(5);
    }

    @Test
    void listAvailable_quandLimiteVingt_alorsVingtLivres() {
        // Given
        givenManyAvailableBooks(30);
        // When
        List<Book> result = service.listAvailable(ListAvailableBooksQuery.of(20));
        // Then
        assertThat(result).hasSize(20);
    }

    @Test
    void listAvailable_quandLimiteAbsente_alorsDixLivres() {
        // Given
        givenManyAvailableBooks(30);
        // When
        List<Book> result = service.listAvailable(ListAvailableBooksQuery.of(null));
        // Then
        assertThat(result).hasSize(10);
        assertThat(repository.lastMaxResults()).isEqualTo(10);
    }

    @Test
    void listAvailable_quandCatalogueVide_alorsListeVide() {
        // When
        List<Book> result = service.listAvailable(ListAvailableBooksQuery.of(10));
        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void listAvailable_quandAucunDisponible_alorsListeVide() {
        // Given
        givenBook("9782070368228", "Epuise", 2, 0);
        // When
        List<Book> result = service.listAvailable(ListAvailableBooksQuery.of(null));
        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void of_quandLimiteZero_alorsExceptionSansLectureDuCatalogue() {
        // Given
        givenManyAvailableBooks(3);
        // When / Then
        assertThatThrownBy(() -> ListAvailableBooksQuery.of(0)).isInstanceOf(LimitOutOfRangeException.class);
        assertThat(repository.loadAvailableCalls()).isZero();
    }

    @Test
    void of_quandLimiteVingtEtUn_alorsExceptionSansLectureDuCatalogue() {
        // When / Then
        assertThatThrownBy(() -> ListAvailableBooksQuery.of(21)).isInstanceOf(LimitOutOfRangeException.class);
        assertThat(repository.loadAvailableCalls()).isZero();
    }

    @Test
    void listAvailable_quandPortMockeRenvoieNonTrieAvecEpuisesEtTropDeLivres_alorsSelectionFaiteParLeDomaine() {
        // Given : le port renvoie volontairement du désordre (non trié, épuisés, plus que la limite)
        LoadAvailableBooksPort port = mock(LoadAvailableBooksPort.class);
        ListAvailableBooksService mockedService = new ListAvailableBooksService(port);
        when(port.loadAvailableBooks(anyInt())).thenReturn(List.of(
                Book.restore(BookId.newId(), Isbn.of("9782070368259"), "zebre", "A", 2, 1),
                Book.restore(BookId.newId(), Isbn.of("9782070368235"), "Epuise", "A", 2, 0),
                Book.restore(BookId.newId(), Isbn.of("9782070368242"), "beta", "A", 1, 1),
                Book.restore(BookId.newId(), Isbn.of("9782070368266"), "Beta", "A", 1, 1),
                Book.restore(BookId.newId(), Isbn.of("9782070368228"), "Alpha", "A", 3, 3),
                Book.restore(BookId.newId(), Isbn.of("9782070368273"), "Aardvark", "A", 1, 0)));
        // When
        List<Book> result = mockedService.listAvailable(ListAvailableBooksQuery.of(3));
        // Then : disponibles uniquement, titre (casse ignorée) puis ISBN, tronqués à la limite
        assertThat(result).extracting(b -> b.getIsbn().value())
                .containsExactly("9782070368228", "9782070368242", "9782070368266");
        assertThat(result).allMatch(b -> b.getAvailableCopies() >= 1);
        verify(port).loadAvailableBooks(3);
    }

    @Test
    void listAvailable_quandLimiteAbsente_alorsPortInterrogeAvecDix() {
        // Given
        LoadAvailableBooksPort port = mock(LoadAvailableBooksPort.class);
        when(port.loadAvailableBooks(anyInt())).thenReturn(List.of());
        // When
        new ListAvailableBooksService(port).listAvailable(ListAvailableBooksQuery.of(null));
        // Then
        verify(port).loadAvailableBooks(10);
    }

    @Test
    void usecase_quandAnnote_alorsTransactionEnLectureSeule() {
        // When
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(
                ListAvailableBooksService.class, Transactional.class);
        // Then
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }
}
