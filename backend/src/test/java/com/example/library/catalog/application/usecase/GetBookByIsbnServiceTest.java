package com.example.library.catalog.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.example.library.catalog.application.fake.InMemoryBookRepository;
import com.example.library.catalog.application.port.in.GetBookByIsbnQuery;
import com.example.library.catalog.application.port.out.LoadBookByIsbnPort;
import org.mockito.ArgumentCaptor;
import com.example.library.catalog.domain.exception.BookNotFoundException;
import com.example.library.catalog.domain.exception.InvalidIsbnException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

/** RM-MCP-04, RM-MCP-05, RM-MCP-13 : orchestration de la consultation d'un livre par ISBN. */
class GetBookByIsbnServiceTest {

    private final InMemoryBookRepository repository = new InMemoryBookRepository();
    private final GetBookByIsbnService service = new GetBookByIsbnService(repository);

    @Test
    void getByIsbn_quandLivreExistant_alorsLivreRenvoye() {
        // Given
        Book book = Book.restore(BookId.newId(), Isbn.of("9782070368228"), "L'Étranger", "Albert Camus", 3, 2);
        repository.givenExisting(book);
        // When
        Book result = service.getByIsbn(GetBookByIsbnQuery.fromRaw("9782070368228"));
        // Then
        assertThat(result).isSameAs(book);
        assertThat(result.getAvailableCopies()).isEqualTo(2);
        assertThat(result.getTotalCopies()).isEqualTo(3);
    }

    @Test
    void getByIsbn_quandIsbnFormateAvecTirets_alorsMemeLivre() {
        // Given
        Book book = Book.restore(BookId.newId(), Isbn.of("9782070368228"), "L'Étranger", "Albert Camus", 3, 2);
        repository.givenExisting(book);
        // When
        Book result = service.getByIsbn(GetBookByIsbnQuery.fromRaw("978-2-07-036822-8"));
        // Then
        assertThat(result).isSameAs(book);
        assertThat(result.getIsbn().value()).isEqualTo("9782070368228");
    }

    @Test
    void getByIsbn_quandAucunExemplaireDisponible_alorsLivreQuandMemeRenvoye() {
        // Given
        Book book = Book.restore(BookId.newId(), Isbn.of("9782070368228"), "T", "A", 2, 0);
        repository.givenExisting(book);
        // When
        Book result = service.getByIsbn(GetBookByIsbnQuery.fromRaw("9782070368228"));
        // Then
        assertThat(result.getAvailableCopies()).isZero();
        assertThat(result.getTotalCopies()).isEqualTo(2);
    }

    @Test
    void getByIsbn_quandIsbnInconnu_alorsBookNotFound() {
        // Given : catalogue vide
        GetBookByIsbnQuery query = GetBookByIsbnQuery.fromRaw("9780000000002");
        // When / Then
        assertThatThrownBy(() -> service.getByIsbn(query))
                .isInstanceOfSatisfying(BookNotFoundException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("BOOK_NOT_FOUND");
                    assertThat(e.getIsbn().value()).isEqualTo("9780000000002");
                });
    }

    @Test
    void fromRaw_quandIsbnInvalide_alorsExceptionAvantToutAccesAuCatalogue() {
        // When / Then
        assertThatThrownBy(() -> GetBookByIsbnQuery.fromRaw("978207036822"))
                .isInstanceOf(InvalidIsbnException.class);
        assertThat(repository.loadByIsbnCalls()).isZero();
    }

    @Test
    void getByIsbn_quandPortMockeRenvoieVide_alorsBookNotFoundEtPortRecoitIsbnNormalise() {
        // Given
        LoadBookByIsbnPort port = mock(LoadBookByIsbnPort.class);
        when(port.loadByIsbn(any())).thenReturn(Optional.empty());
        GetBookByIsbnService mockedService = new GetBookByIsbnService(port);
        GetBookByIsbnQuery query = GetBookByIsbnQuery.fromRaw("978-2-07-036822-8");
        // When / Then
        assertThatThrownBy(() -> mockedService.getByIsbn(query))
                .isInstanceOfSatisfying(BookNotFoundException.class,
                        e -> assertThat(e.getIsbn().value()).isEqualTo("9782070368228"));
        ArgumentCaptor<Isbn> captor = ArgumentCaptor.forClass(Isbn.class);
        verify(port).loadByIsbn(captor.capture());
        assertThat(captor.getValue().value()).isEqualTo("9782070368228");
    }

    @Test
    void getByIsbn_quandPortMockeRenvoieLeLivre_alorsLivreRenvoye() {
        // Given
        LoadBookByIsbnPort port = mock(LoadBookByIsbnPort.class);
        Book book = Book.restore(BookId.newId(), Isbn.of("9782070368228"), "T", "A", 1, 1);
        when(port.loadByIsbn(Isbn.of("9782070368228"))).thenReturn(Optional.of(book));
        // When
        Book result = new GetBookByIsbnService(port).getByIsbn(GetBookByIsbnQuery.fromRaw("978-2-07-036822-8"));
        // Then
        assertThat(result).isSameAs(book);
    }

    @Test
    void usecase_quandAnnote_alorsTransactionEnLectureSeule() {
        // When
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(
                GetBookByIsbnService.class, Transactional.class);
        // Then : l'alias @UseCase(readOnly = true) se propage a @Transactional
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }
}
