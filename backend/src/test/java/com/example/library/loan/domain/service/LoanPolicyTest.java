package com.example.library.loan.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.example.library.loan.domain.exception.BookAlreadyBorrowedException;
import com.example.library.loan.domain.exception.LoanLimitReachedException;
import com.example.library.loan.domain.exception.MemberHasOverdueLoanException;
import com.example.library.loan.domain.model.BookId;
import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.LoanId;
import com.example.library.loan.domain.model.MemberId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** RM-01 (RM-LOAN-02), RM-02 (RM-LOAN-03), RM-LOAN-28 et ordre d'évaluation RM-LOAN-06 (lignes 2 à 4). Java pur. */
class LoanPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private static final MemberId MEMBER = new MemberId(UUID.randomUUID());

    private final LoanPolicy policy = new LoanPolicy();

    private static BookId newBook() {
        return new BookId(UUID.randomUUID());
    }

    /** Emprunt actif, échéance dans {@code dueInDays} jours (négatif : en retard). */
    private static Loan active(BookId book, int dueInDays) {
        LocalDate dueOn = TODAY.plusDays(dueInDays);
        return Loan.restore(LoanId.newId(), MEMBER, book, dueOn.minusDays(14), dueOn, null);
    }

    private static List<Loan> activeLoans(int count) {
        return IntStream.range(0, count).mapToObj(i -> active(newBook(), 7)).toList();
    }

    // ---- RM-LOAN-02 : limite de 3

    @Test
    void checkCanBorrow_quandTroisEmpruntsActifs_alorsLoanLimitReached() {
        // Given
        List<Loan> loans = activeLoans(3);
        // When / Then
        assertThatThrownBy(() -> policy.checkCanBorrow(loans, newBook(), TODAY))
                .isInstanceOf(LoanLimitReachedException.class)
                .extracting("code").isEqualTo("LOAN_LIMIT_REACHED");
        assertThat(LoanPolicy.MAX_ACTIVE_LOANS).isEqualTo(3);
    }

    @Test
    void checkCanBorrow_quandDeuxEmpruntsActifs_alorsAccepte() {
        // Given / When / Then
        assertThatCode(() -> policy.checkCanBorrow(activeLoans(2), newBook(), TODAY)).doesNotThrowAnyException();
    }

    @Test
    void checkCanBorrow_quandAucunEmpruntActif_alorsAccepte() {
        // Given / When / Then
        assertThatCode(() -> policy.checkCanBorrow(List.of(), newBook(), TODAY)).doesNotThrowAnyException();
    }

    @Test
    void checkCanBorrow_quandDeuxActifsEtUnRenduLeJourMeme_alorsAccepte() {
        // Given : seuls les emprunts actifs sont transmis ; le troisième, rendu aujourd'hui, ne compte pas
        Loan returnedToday = active(newBook(), 7);
        returnedToday.giveBack(TODAY);
        List<Loan> all = new ArrayList<>(activeLoans(2));
        all.add(returnedToday);
        List<Loan> activeOnly = all.stream().filter(Loan::isActive).toList();
        assertThat(activeOnly).hasSize(2);
        // When / Then
        assertThatCode(() -> policy.checkCanBorrow(activeOnly, newBook(), TODAY)).doesNotThrowAnyException();
    }

    // ---- RM-LOAN-03 : retard

    @Test
    void checkCanBorrow_quandEmpruntEnRetard_alorsMemberHasOverdueLoan() {
        // Given : échéance hier
        List<Loan> loans = List.of(active(newBook(), -1));
        // When / Then
        assertThatThrownBy(() -> policy.checkCanBorrow(loans, newBook(), TODAY))
                .isInstanceOf(MemberHasOverdueLoanException.class)
                .extracting("code").isEqualTo("MEMBER_HAS_OVERDUE_LOAN");
    }

    @Test
    void checkCanBorrow_quandEcheanceAujourdhui_alorsPasEnRetardEtAccepte() {
        // Given : le jour de l'échéance, l'emprunt n'est pas en retard
        List<Loan> loans = List.of(active(newBook(), 0));
        // When / Then
        assertThatCode(() -> policy.checkCanBorrow(loans, newBook(), TODAY)).doesNotThrowAnyException();
    }

    // ---- RM-LOAN-28 : même livre

    @Test
    void checkCanBorrow_quandMemeLivreDejaActif_alorsBookAlreadyBorrowed() {
        // Given
        BookId book = newBook();
        List<Loan> loans = List.of(active(book, 7), active(newBook(), 7));
        // When / Then
        assertThatThrownBy(() -> policy.checkCanBorrow(loans, book, TODAY))
                .isInstanceOf(BookAlreadyBorrowedException.class)
                .extracting("code").isEqualTo("BOOK_ALREADY_BORROWED");
    }

    @Test
    void checkCanBorrow_quandMemeLivreDejaRendu_alorsAccepte() {
        // Given : le livre a été rendu, il n'est plus dans les emprunts actifs transmis
        BookId book = newBook();
        Loan returned = active(book, 7);
        returned.giveBack(TODAY);
        List<Loan> activeOnly = List.of(returned).stream().filter(Loan::isActive).toList();
        // When / Then
        assertThatCode(() -> policy.checkCanBorrow(activeOnly, book, TODAY)).doesNotThrowAnyException();
    }

    // ---- RM-LOAN-06 : ordre d'évaluation (retard, limite, déjà emprunté)

    record Situation(String name, boolean overdue, int activeCount, boolean alreadyBorrowed, Class<?> expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Situation> situations() {
        return List.of(
                new Situation("retard + 3 actifs + déjà emprunté : le retard d'abord", true, 3, true,
                        MemberHasOverdueLoanException.class),
                new Situation("retard + 2 actifs : le retard", true, 2, false, MemberHasOverdueLoanException.class),
                new Situation("aucun retard, 3 actifs dont ce livre : la limite avant déjà emprunté", false, 3, true,
                        LoanLimitReachedException.class),
                new Situation("aucun retard, 2 actifs dont ce livre : déjà emprunté", false, 2, true,
                        BookAlreadyBorrowedException.class));
    }

    @ParameterizedTest
    @MethodSource("situations")
    void checkCanBorrow_quandPlusieursReglesViolees_alorsLaPremiereDeLOrdreDetermineLeRefus(Situation situation) {
        // Given : la situation décrit le nombre d'emprunts actifs, un éventuel retard et le livre visé déjà emprunté
        BookId target = newBook();
        List<Loan> loans = new ArrayList<>();
        int others = situation.activeCount() - (situation.alreadyBorrowed() ? 1 : 0);
        for (int i = 0; i < others; i++) {
            loans.add(active(newBook(), situation.overdue() && i == 0 ? -1 : 7));
        }
        if (situation.alreadyBorrowed()) {
            loans.add(active(target, situation.overdue() && loans.isEmpty() ? -1 : 7));
        }
        assertThat(loans).hasSize(situation.activeCount());
        // When / Then
        assertThatThrownBy(() -> policy.checkCanBorrow(loans, target, TODAY)).isInstanceOf(situation.expected());
    }
}
