package com.example.library.loan.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.example.library.loan.domain.exception.LoanAlreadyReturnedException;
import org.junit.jupiter.api.Test;

/** RM-03 (RM-LOAN-04), RM-02 (RM-LOAN-05), RM-05 (RM-LOAN-10, 11), ordre d'affichage (RM-LOAN-14). Java pur. */
class LoanTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private static final MemberId MEMBER = new MemberId(UUID.randomUUID());
    private static final BookId BOOK = new BookId(UUID.randomUUID());

    private static Loan activeDueOn(LocalDate dueOn) {
        return Loan.restore(LoanId.newId(), MEMBER, BOOK, dueOn.minusDays(14), dueOn, null);
    }

    private static Loan withId(String id, LocalDate dueOn) {
        return Loan.restore(new LoanId(UUID.fromString(id)), MEMBER, BOOK, dueOn.minusDays(14), dueOn, null);
    }

    // ---- RM-LOAN-04 : échéance à J+14

    @Test
    void open_quandLe24Novembre2026_alorsEcheanceLe8Decembre2026() {
        // Given / When
        Loan loan = Loan.open(MEMBER, BOOK, TODAY);
        // Then
        assertThat(loan.getBorrowedOn()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(loan.getDueOn()).isEqualTo(LocalDate.of(2026, 12, 8));
        assertThat(Loan.LOAN_DURATION_DAYS).isEqualTo(14);
    }

    @Test
    void open_quandChangementDAnnee_alorsEcheanceDansLAnneeSuivante() {
        // Given / When
        Loan loan = Loan.open(MEMBER, BOOK, LocalDate.of(2026, 12, 25));
        // Then
        assertThat(loan.getDueOn()).isEqualTo(LocalDate.of(2027, 1, 8));
    }

    @Test
    void open_quandNouvelEmprunt_alorsActifSansDateDeRetourEtMembreEtLivreConserves() {
        // Given / When
        Loan loan = Loan.open(MEMBER, BOOK, TODAY);
        // Then
        assertThat(loan.getId()).isNotNull();
        assertThat(loan.getMemberId()).isEqualTo(MEMBER);
        assertThat(loan.getBookId()).isEqualTo(BOOK);
        assertThat(loan.getReturnedOn()).isNull();
        assertThat(loan.isActive()).isTrue();
    }

    @Test
    void open_quandDeuxAppels_alorsIdentifiantsDifferents() {
        // Given / When / Then
        assertThat(Loan.open(MEMBER, BOOK, TODAY).getId()).isNotEqualTo(Loan.open(MEMBER, BOOK, TODAY).getId());
    }

    @Test
    void open_quandDateNulle_alorsNullPointerException() {
        // When / Then
        assertThatThrownBy(() -> Loan.open(MEMBER, BOOK, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void restore_quandDonneesIncoherentes_alorsAucuneRegleRejouee() {
        // Given : échéance antérieure à l'emprunt et retour déjà renseigné ; la reconstitution ne rejoue aucune règle
        LocalDate borrowed = LocalDate.of(2026, 11, 1);
        // When
        Loan loan = Loan.restore(LoanId.newId(), MEMBER, BOOK, borrowed, borrowed.minusDays(3), borrowed.plusDays(2));
        // Then
        assertThat(loan.getDueOn()).isEqualTo(borrowed.minusDays(3));
        assertThat(loan.getReturnedOn()).isEqualTo(borrowed.plusDays(2));
        assertThat(loan.isActive()).isFalse();
    }

    // ---- RM-LOAN-05 : retard

    @Test
    void isOverdue_quandJourDeLEcheance_alorsFaux() {
        // Given : échéance aujourd'hui
        Loan loan = activeDueOn(TODAY);
        // When / Then
        assertThat(loan.isOverdue(TODAY)).isFalse();
    }

    @Test
    void isOverdue_quandLendemainDeLEcheance_alorsVrai() {
        // Given : échéance hier
        Loan loan = activeDueOn(TODAY.minusDays(1));
        // When / Then
        assertThat(loan.isOverdue(TODAY)).isTrue();
    }

    @Test
    void isOverdue_quandEcheanceDansLeFutur_alorsFaux() {
        // Given / When / Then
        assertThat(activeDueOn(TODAY.plusDays(1)).isOverdue(TODAY)).isFalse();
    }

    @Test
    void isOverdue_quandRenduApresEcheance_alorsFaux() {
        // Given : emprunt en retard, puis rendu
        Loan loan = activeDueOn(TODAY.minusDays(5));
        assertThat(loan.isOverdue(TODAY)).isTrue();
        // When
        loan.giveBack(TODAY);
        // Then : un emprunt rendu n'est jamais en retard
        assertThat(loan.isOverdue(TODAY)).isFalse();
    }

    // ---- RM-LOAN-10, 11 : retour

    @Test
    void giveBack_quandEmpruntActif_alorsDateDeRetourRenseigneeEtInactif() {
        // Given
        Loan loan = Loan.open(MEMBER, BOOK, TODAY);
        // When
        loan.giveBack(TODAY.plusDays(3));
        // Then
        assertThat(loan.getReturnedOn()).isEqualTo(TODAY.plusDays(3));
        assertThat(loan.isActive()).isFalse();
    }

    @Test
    void giveBack_quandEmpruntEnRetard_alorsAccepte() {
        // Given : RM-LOAN-12, aucune pénalité
        Loan loan = activeDueOn(TODAY.minusDays(10));
        // When
        loan.giveBack(TODAY);
        // Then
        assertThat(loan.getReturnedOn()).isEqualTo(TODAY);
    }

    @Test
    void giveBack_quandDejaRendu_alorsLoanAlreadyReturnedEtDateInchangee() {
        // Given
        Loan loan = Loan.open(MEMBER, BOOK, TODAY);
        loan.giveBack(TODAY);
        // When / Then
        assertThatThrownBy(() -> loan.giveBack(TODAY.plusDays(1)))
                .isInstanceOf(LoanAlreadyReturnedException.class)
                .extracting("code").isEqualTo("LOAN_ALREADY_RETURNED");
        assertThat(loan.getReturnedOn()).isEqualTo(TODAY);
    }

    @Test
    void concerns_quandMemeLivreOuAutre_alorsVraiOuFaux() {
        // Given
        Loan loan = Loan.open(MEMBER, BOOK, TODAY);
        // When / Then
        assertThat(loan.concerns(new BookId(BOOK.value()))).isTrue();
        assertThat(loan.concerns(new BookId(UUID.randomUUID()))).isFalse();
    }

    // ---- RM-LOAN-14 : ordre d'affichage

    @Test
    void displayOrder_quandEcheancesDifferentes_alorsEcheanceCroissante() {
        // Given
        Loan late = activeDueOn(TODAY.plusDays(10));
        Loan early = activeDueOn(TODAY.plusDays(2));
        Loan middle = activeDueOn(TODAY.plusDays(5));
        // When
        List<Loan> sorted = List.of(late, early, middle).stream().sorted(Loan.DISPLAY_ORDER).toList();
        // Then
        assertThat(sorted).containsExactly(early, middle, late);
    }


    @Test
    void displayOrder_quandMemeEcheance_alorsOrdreHexadecimalDesIdentifiantsCommePostgreSql() {
        // Given : UUID.compareTo est signé (ffff... avant 0000...), l'ordre de PostgreSQL ne l'est pas
        Loan high = withId("ffffffff-ffff-ffff-ffff-ffffffffffff", TODAY.plusDays(3));
        Loan low = withId("00000000-0000-0000-0000-000000000001", TODAY.plusDays(3));
        Loan middle = withId("7fffffff-ffff-ffff-ffff-ffffffffffff", TODAY.plusDays(3));
        // When
        List<Loan> sorted = List.of(high, middle, low).stream().sorted(Loan.DISPLAY_ORDER).toList();
        // Then
        assertThat(sorted).containsExactly(low, middle, high);
    }

    @Test
    void displayOrder_quandEcheanceDifferenteEtIdentifiantInverse_alorsEcheancePrioritaire() {
        // Given
        Loan earlyHighId = withId("ffffffff-ffff-ffff-ffff-ffffffffffff", TODAY.plusDays(1));
        Loan lateLowId = withId("00000000-0000-0000-0000-000000000001", TODAY.plusDays(2));
        // When
        List<Loan> sorted = List.of(lateLowId, earlyHighId).stream().sorted(Loan.DISPLAY_ORDER).toList();
        // Then
        assertThat(sorted).containsExactly(earlyHighId, lateLowId);
    }
}
