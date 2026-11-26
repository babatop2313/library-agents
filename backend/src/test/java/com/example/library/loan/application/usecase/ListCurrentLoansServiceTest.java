package com.example.library.loan.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.example.library.loan.application.fake.FakeCatalog;
import com.example.library.loan.application.fake.InMemoryLoanRepository;
import com.example.library.loan.application.port.in.CurrentLoanView;
import com.example.library.loan.application.port.in.ListCurrentLoansQuery;
import com.example.library.loan.domain.model.BookId;
import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.LoanId;
import com.example.library.loan.domain.model.MemberId;
import org.junit.jupiter.api.Test;

/** Consultation : RM-LOAN-14 (actifs, tri, indicateur de retard) et RM-LOAN-32 (un seul appel groupé au catalogue). */
class ListCurrentLoansServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneId.of("Africa/Dakar"));

    private final InMemoryLoanRepository loans = new InMemoryLoanRepository();
    private final FakeCatalog catalog = new FakeCatalog();
    private final ListCurrentLoansService service = new ListCurrentLoansService(loans, catalog, CLOCK);
    private final MemberId member = new MemberId(UUID.randomUUID());

    private Loan givenLoan(MemberId owner, BookId book, int dueInDays, LocalDate returnedOn) {
        LocalDate dueOn = TODAY.plusDays(dueInDays);
        Loan loan = Loan.restore(LoanId.newId(), owner, book, dueOn.minusDays(14), dueOn, returnedOn);
        loans.givenLoan(loan);
        return loan;
    }

    private List<CurrentLoanView> list() {
        return service.listCurrentLoans(new ListCurrentLoansQuery(member));
    }

    @Test
    void listCurrentLoans_quandAucunEmprunt_alorsListeVideEtAucunAppelAuCatalogue() {
        // Given : un autre membre a un emprunt, pas celui-ci
        givenLoan(new MemberId(UUID.randomUUID()), catalog.givenBook("Autre", 1), 7, null);
        // When
        List<CurrentLoanView> result = list();
        // Then : RM-LOAN-32
        assertThat(result).isEmpty();
        assertThat(catalog.summaryRequests()).isEmpty();
    }

    @Test
    void listCurrentLoans_quandTroisEmprunts_alorsUnSeulAppelAuCatalogueAvecLesTroisIdentifiants() {
        // Given
        BookId a = catalog.givenBook("A", 1);
        BookId b = catalog.givenBook("B", 1);
        BookId c = catalog.givenBook("C", 1);
        givenLoan(member, a, 3, null);
        givenLoan(member, b, 5, null);
        givenLoan(member, c, 7, null);
        // When
        List<CurrentLoanView> result = list();
        // Then : RM-LOAN-32, pas de N+1
        assertThat(result).hasSize(3);
        assertThat(catalog.summaryRequests()).hasSize(1);
        assertThat(catalog.summaryRequests().get(0)).containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    void listCurrentLoans_quandEmpruntsActifsEtRendusEtAutreMembre_alorsSeulsLesActifsDuMembre() {
        // Given : 2 actifs, 1 rendu, 1 actif d'un autre membre
        BookId a = catalog.givenBook("A", 1);
        BookId b = catalog.givenBook("B", 1);
        BookId c = catalog.givenBook("C", 1);
        givenLoan(member, a, 3, null);
        givenLoan(member, b, 5, null);
        givenLoan(member, c, 7, TODAY);
        givenLoan(new MemberId(UUID.randomUUID()), catalog.givenBook("D", 1), 2, null);
        // When
        List<CurrentLoanView> result = list();
        // Then
        assertThat(result).extracting(CurrentLoanView::bookId).containsExactly(a, b);
        assertThat(catalog.summaryRequests().get(0)).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void listCurrentLoans_quandEmpruntsDansLeDesordre_alorsTriesParEcheanceCroissante() {
        // Given
        BookId late = catalog.givenBook("Tard", 1);
        BookId early = catalog.givenBook("Tôt", 1);
        BookId middle = catalog.givenBook("Milieu", 1);
        givenLoan(member, late, 10, null);
        givenLoan(member, early, 1, null);
        givenLoan(member, middle, 5, null);
        // When
        List<CurrentLoanView> result = list();
        // Then
        assertThat(result).extracting(CurrentLoanView::bookId).containsExactly(early, middle, late);
    }

    @Test
    void listCurrentLoans_quandMemeEcheance_alorsDepartageParIdentifiant() {
        // Given
        BookId a = catalog.givenBook("A", 1);
        BookId b = catalog.givenBook("B", 1);
        Loan high = Loan.restore(new LoanId(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")), member, a,
                TODAY.minusDays(7), TODAY.plusDays(7), null);
        Loan low = Loan.restore(new LoanId(UUID.fromString("00000000-0000-0000-0000-000000000001")), member, b,
                TODAY.minusDays(7), TODAY.plusDays(7), null);
        loans.givenLoan(high);
        loans.givenLoan(low);
        // When
        List<CurrentLoanView> result = list();
        // Then
        assertThat(result).extracting(CurrentLoanView::id).containsExactly(low.getId(), high.getId());
    }

    @Test
    void listCurrentLoans_quandLivreAbsentDuLot_alorsEmpruntListeAvecChampsDuLivreNuls() {
        // Given : le livre a été supprimé du catalogue
        BookId vanished = catalog.givenBook("Disparu", 1);
        BookId present = catalog.givenBook("Présent", 1);
        givenLoan(member, vanished, 2, null);
        givenLoan(member, present, 4, null);
        catalog.removeBook(vanished);
        // When
        List<CurrentLoanView> result = list();
        // Then : RM-LOAN-32, aucune erreur
        assertThat(result).hasSize(2);
        CurrentLoanView first = result.get(0);
        assertThat(first.bookId()).isEqualTo(vanished);
        assertThat(first.isbn()).isNull();
        assertThat(first.title()).isNull();
        assertThat(first.author()).isNull();
        assertThat(result.get(1).title()).isEqualTo("Présent");
    }

    @Test
    void listCurrentLoans_quandEcheanceHier_alorsOverdueVrai() {
        // Given : RM-LOAN-05
        givenLoan(member, catalog.givenBook("Hier", 1), -1, null);
        givenLoan(member, catalog.givenBook("Aujourd'hui", 1), 0, null);
        givenLoan(member, catalog.givenBook("Demain", 1), 1, null);
        // When
        List<CurrentLoanView> result = list();
        // Then : seul l'emprunt dont l'échéance est strictement passée est en retard
        assertThat(result).extracting(CurrentLoanView::title).containsExactly("Hier", "Aujourd'hui", "Demain");
        assertThat(result).extracting(CurrentLoanView::overdue).containsExactly(true, false, false);
    }

    @Test
    void listCurrentLoans_quandEmpruntExistant_alorsToutesLesInformationsDuLivreEtDesDatesRecopiees() {
        // Given
        BookId book = catalog.givenBook("L'Étranger", 1);
        Loan loan = givenLoan(member, book, 7, null);
        // When
        CurrentLoanView view = list().get(0);
        // Then
        assertThat(view.id()).isEqualTo(loan.getId());
        assertThat(view.bookId()).isEqualTo(book);
        assertThat(view.title()).isEqualTo("L'Étranger");
        assertThat(view.author()).isEqualTo("Auteur de L'Étranger");
        assertThat(view.isbn()).isNotBlank();
        assertThat(view.borrowedOn()).isEqualTo(loan.getBorrowedOn());
        assertThat(view.dueOn()).isEqualTo(TODAY.plusDays(7));
        assertThat(view.overdue()).isFalse();
    }
}
