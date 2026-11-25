package com.example.library.catalog.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.library.catalog.application.fake.InMemoryBookRepository;
import com.example.library.catalog.application.port.in.CopyReleaseOutcome;
import com.example.library.catalog.application.port.in.ReleaseCopyCommand;
import com.example.library.catalog.domain.exception.AvailableCopiesOverflowException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.Test;

/** RM-05 (RM-LOAN-10) : restitution d'un exemplaire, jamais au-delà du total. */
class ReleaseCopyServiceTest {

    private final InMemoryBookRepository repository = new InMemoryBookRepository();
    private final ReleaseCopyService service = new ReleaseCopyService(repository, repository);

    private Book givenBook(int total, int available) {
        Book book = Book.restore(BookId.newId(), Isbn.of("9782070368228"), "T", "A", total, available);
        repository.givenExisting(book);
        return book;
    }

    private CopyReleaseOutcome release(BookId id) {
        return service.release(new ReleaseCopyCommand(id));
    }

    @Test
    void release_quandUnExemplaireEmprunte_alorsReleasedEtUnExemplaireDePlus() {
        // Given
        Book book = givenBook(3, 0);
        // When
        CopyReleaseOutcome outcome = release(book.getId());
        // Then
        assertThat(outcome).isEqualTo(CopyReleaseOutcome.RELEASED);
        assertThat(repository.find(book.getIsbn()).getAvailableCopies()).isEqualTo(1);
        assertThat(repository.find(book.getIsbn()).getTotalCopies()).isEqualTo(3);
    }

    @Test
    void release_quandLivreInconnu_alorsBookNotFoundSansException() {
        // Given / When
        CopyReleaseOutcome outcome = release(BookId.newId());
        // Then : issue attendue, pas d'exception (la transaction de loan reste valide)
        assertThat(outcome).isEqualTo(CopyReleaseOutcome.BOOK_NOT_FOUND);
        assertThat(repository.incrementCalls()).isZero();
    }

    @Test
    void release_quandTousExemplairesDejaDisponibles_alorsAvailableCopiesOverflowSansEcriture() {
        // Given : incohérence de données, erreur technique qui annule la transaction de l'appelant
        Book book = givenBook(3, 3);
        // When / Then
        assertThatThrownBy(() -> release(book.getId()))
                .isInstanceOf(AvailableCopiesOverflowException.class)
                .extracting("code").isEqualTo("AVAILABLE_COPIES_OVERFLOW");
        assertThat(repository.incrementCalls()).isZero();
        assertThat(repository.find(book.getIsbn()).getAvailableCopies()).isEqualTo(3);
    }

    @Test
    void release_quandMiseAJourConditionnelleSansEffet_alorsAvailableCopiesOverflow() {
        // Given : le domaine accepte (2/3) mais la base ne modifie aucune ligne (livre supprimé ou stock changé
        // entre la lecture et l'écriture) : on ne feint pas la réussite
        Book book = givenBook(3, 2);
        InMemoryBookRepository noEffect = new InMemoryBookRepository() {
            @Override
            public boolean incrementAvailableCopies(BookId id) {
                return false;
            }
        };
        noEffect.givenExisting(book);
        ReleaseCopyService strict = new ReleaseCopyService(noEffect, noEffect);
        // When / Then
        assertThatThrownBy(() -> strict.release(new ReleaseCopyCommand(book.getId())))
                .isInstanceOf(AvailableCopiesOverflowException.class)
                .hasMessageContaining("Restitution impossible");
    }
}
