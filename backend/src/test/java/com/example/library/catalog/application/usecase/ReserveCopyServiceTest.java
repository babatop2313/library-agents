package com.example.library.catalog.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.library.catalog.application.fake.InMemoryBookRepository;
import com.example.library.catalog.application.port.in.CopyReservationOutcome;
import com.example.library.catalog.application.port.in.ReserveCopyCommand;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * RM-04 (RM-LOAN-01, 08, 31) : prise d'un exemplaire. Les cas attendus sont des issues, jamais des exceptions
 * (sinon la transaction de {@code loan} serait marquée rollback-only).
 */
class ReserveCopyServiceTest {

    private final InMemoryBookRepository repository = new InMemoryBookRepository();
    private final ReserveCopyService service = new ReserveCopyService(repository, repository);

    private Book givenBook(int total, int available) {
        Book book = Book.restore(BookId.newId(), Isbn.of("9782070368228"), "T", "A", total, available);
        repository.givenExisting(book);
        return book;
    }

    private CopyReservationOutcome reserve(BookId id) {
        return service.reserve(new ReserveCopyCommand(id));
    }

    @Test
    void reserve_quandExemplaireDisponible_alorsReservedEtUnExemplaireDeMoins() {
        // Given
        Book book = givenBook(3, 2);
        // When
        CopyReservationOutcome outcome = reserve(book.getId());
        // Then
        assertThat(outcome).isEqualTo(CopyReservationOutcome.RESERVED);
        assertThat(repository.find(book.getIsbn()).getAvailableCopies()).isEqualTo(1);
        assertThat(repository.find(book.getIsbn()).getTotalCopies()).isEqualTo(3);
    }

    @Test
    void reserve_quandLivreInconnu_alorsBookNotFoundSansException() {
        // Given / When
        CopyReservationOutcome outcome = reserve(BookId.newId());
        // Then
        assertThat(outcome).isEqualTo(CopyReservationOutcome.BOOK_NOT_FOUND);
        assertThat(repository.decrementCalls()).isZero();
    }

    @Test
    void reserve_quandAucunExemplaireDisponible_alorsNoCopyAvailableSansExceptionNiEcriture() {
        // Given : le domaine refuse (Book.takeCopy), la mise à jour n'est même pas tentée
        Book book = givenBook(3, 0);
        // When
        CopyReservationOutcome outcome = reserve(book.getId());
        // Then
        assertThat(outcome).isEqualTo(CopyReservationOutcome.NO_COPY_AVAILABLE);
        assertThat(repository.decrementCalls()).isZero();
        assertThat(repository.find(book.getIsbn()).getAvailableCopies()).isZero();
    }

    @Test
    void reserve_quandCourseDuDernierExemplairePerdue_alorsNoCopyAvailableSansException() {
        // Given : le livre semble disponible à la lecture, mais la décrémentation conditionnelle renvoie faux
        Book book = givenBook(3, 1);
        repository.simulateLostRace();
        // When
        CopyReservationOutcome outcome = reserve(book.getId());
        // Then
        assertThat(outcome).isEqualTo(CopyReservationOutcome.NO_COPY_AVAILABLE);
        assertThat(repository.decrementCalls()).isEqualTo(1);
    }

    @Test
    void reserve_quandDeuxPrisesSurLeDernierExemplaire_alorsUneSeuleReussit() {
        // Given
        Book book = givenBook(1, 1);
        // When
        CopyReservationOutcome first = reserve(book.getId());
        CopyReservationOutcome second = reserve(book.getId());
        // Then
        assertThat(first).isEqualTo(CopyReservationOutcome.RESERVED);
        assertThat(second).isEqualTo(CopyReservationOutcome.NO_COPY_AVAILABLE);
        assertThat(repository.find(book.getIsbn()).getAvailableCopies()).isZero();
    }

    @Test
    void usecase_quandAnnote_alorsTransactionnelEcriture() {
        // When
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(ReserveCopyService.class,
                Transactional.class);
        // Then : la réservation rejoint la transaction de l'emprunt
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isFalse();
    }
}
