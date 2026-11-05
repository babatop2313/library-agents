package com.example.library.catalog.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.library.catalog.application.fake.InMemoryBookRepository;
import com.example.library.catalog.application.port.in.RegisterBookCommand;
import com.example.library.catalog.domain.exception.InvalidBookException;
import com.example.library.catalog.domain.exception.IsbnAlreadyExistsException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Orchestration de l'enregistrement : RM-07, RM-CAT-04, RM-CAT-08, RM-CAT-09, RM-CAT-10. */
class RegisterBookServiceTest {

    private static final Isbn ISBN = Isbn.of("9782070368228");

    private InMemoryBookRepository repository;
    private RegisterBookService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBookRepository();
        service = new RegisterBookService(repository, repository);
    }

    @Test
    void register_quandIsbnLibre_alorsLivreSauveAvecTousExemplairesDisponibles() {
        // Given
        var command = new RegisterBookCommand(ISBN, "L'Étranger", "Albert Camus", 3);
        // When
        Book book = service.register(command);
        // Then
        assertThat(repository.all()).hasSize(1);
        Book saved = repository.find(ISBN);
        assertThat(saved).isSameAs(book);
        assertThat(saved.getTotalCopies()).isEqualTo(3);
        assertThat(saved.getAvailableCopies()).isEqualTo(3);
    }

    @Test
    void register_quandIsbnDejaPresent_alorsIsbnAlreadyExistsEtStockInchange() {
        // Given : un livre existant avec 3 exemplaires
        Book existing = Book.restore(BookId.newId(), ISBN, "L'Étranger", "Albert Camus", 3, 3);
        repository.givenExisting(existing);
        var command = new RegisterBookCommand(ISBN, "Autre", "Autre", 5);
        // When / Then
        assertThatThrownBy(() -> service.register(command)).isInstanceOf(IsbnAlreadyExistsException.class);
        assertThat(repository.all()).containsExactly(existing);
        assertThat(repository.find(ISBN).getTotalCopies()).isEqualTo(3);
    }

    @Test
    void register_quandIsbnDejaPresentSousAutreForme_alorsIsbnAlreadyExists() {
        // Given : la commande porte un Isbn deja normalise par Isbn.of
        repository.givenExisting(Book.restore(BookId.newId(), ISBN, "T", "A", 3, 3));
        var command = new RegisterBookCommand(Isbn.of("978-2-07-036822-8"), "T", "A", 1);
        // When / Then
        assertThatThrownBy(() -> service.register(command)).isInstanceOf(IsbnAlreadyExistsException.class);
        assertThat(repository.all()).hasSize(1);
    }

    @Test
    void register_quandTitreInvalideEtIsbnExistant_alorsInvalidBookExceptionSansControleUnicite() {
        // Given : RM-CAT-10, le format prime sur l'unicite
        repository.givenExisting(Book.restore(BookId.newId(), ISBN, "T", "A", 3, 3));
        var command = new RegisterBookCommand(ISBN, "", "A", 3);
        // When / Then
        assertThatThrownBy(() -> service.register(command)).isInstanceOf(InvalidBookException.class);
        assertThat(repository.checkCalls()).isZero();
        assertThat(repository.all()).hasSize(1);
    }

    @Test
    void register_quandDonneesInvalides_alorsRienNEstSauve() {
        // Given
        var command = new RegisterBookCommand(ISBN, "  ", "  ", 0);
        // When / Then
        assertThatThrownBy(() -> service.register(command)).isInstanceOf(InvalidBookException.class);
        assertThat(repository.all()).isEmpty();
    }

    @Test
    void register_quandDoublonConcurrentALaSauvegarde_alorsExceptionPropagee() {
        // Given : le controle ne voit rien mais l'insertion echoue
        repository.simulateConcurrentInsert();
        var command = new RegisterBookCommand(ISBN, "T", "A", 1);
        // When / Then
        assertThatThrownBy(() -> service.register(command)).isInstanceOf(IsbnAlreadyExistsException.class);
        assertThat(repository.all()).isEmpty();
    }
}
