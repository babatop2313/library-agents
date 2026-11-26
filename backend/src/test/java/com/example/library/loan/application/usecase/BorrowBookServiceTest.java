package com.example.library.loan.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.library.loan.application.fake.FakeCatalog;
import com.example.library.loan.application.fake.InMemoryLoanRepository;
import com.example.library.loan.application.port.in.BorrowBookCommand;
import com.example.library.loan.application.port.out.CopyReservation;
import com.example.library.loan.domain.exception.BookAlreadyBorrowedException;
import com.example.library.loan.domain.exception.BookNotAvailableException;
import com.example.library.loan.domain.exception.BookNotFoundException;
import com.example.library.loan.domain.exception.LoanLimitReachedException;
import com.example.library.loan.domain.exception.MemberHasOverdueLoanException;
import com.example.library.loan.domain.model.BookId;
import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.LoanId;
import com.example.library.loan.domain.model.MemberId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Orchestration de l'emprunt : ports simulés par des fakes, horloge fixe. Les règles elles-mêmes sont testées dans le
 * domaine ; ici on vérifie l'ordre des appels (verrou d'abord, réservation en dernier) et l'absence d'effet en cas de
 * refus. RM-01 à RM-04, RM-LOAN-01 à 09, 28.
 */
class BorrowBookServiceTest {

    private static final ZoneId DAKAR = ZoneId.of("Africa/Dakar");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), DAKAR);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    private final List<String> journal = new java.util.ArrayList<>();
    private final InMemoryLoanRepository loans = new InMemoryLoanRepository(journal);
    private final FakeCatalog catalog = new FakeCatalog(journal);
    private final MemberId member = new MemberId(UUID.randomUUID());
    private final BorrowBookService service = serviceWith(CLOCK);

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(BorrowBookService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
    }

    private BorrowBookService serviceWith(Clock clock) {
        return new BorrowBookService(loans, catalog, loans, catalog, loans, clock);
    }

    private void givenActiveLoan(MemberId owner, BookId book, int dueInDays) {
        LocalDate dueOn = TODAY.plusDays(dueInDays);
        loans.givenLoan(Loan.restore(LoanId.newId(), owner, book, dueOn.minusDays(14), dueOn, null));
    }

    private void givenReturnedLoan(MemberId owner, BookId book) {
        loans.givenLoan(Loan.restore(LoanId.newId(), owner, book, TODAY.minusDays(5), TODAY.plusDays(9), TODAY));
    }

    private Loan borrow(BookId book) {
        return service.borrow(new BorrowBookCommand(member, book));
    }

    private void assertNothingChanged(BookId book, int expectedStock, int expectedLoans) {
        assertThat(catalog.available(book)).isEqualTo(expectedStock);
        assertThat(loans.all()).hasSize(expectedLoans);
        assertThat(loans.saveNewCalls()).isZero();
    }

    // ---- nominal

    @Test
    void borrow_quandMembreSansEmpruntEtLivreDisponible_alorsEmpruntCreeAvecEcheanceEtStockDecremente() {
        // Given : le 2026-11-24 à Dakar, un livre à 2 exemplaires
        BookId book = catalog.givenBook("L'Étranger", 2);
        // When
        Loan loan = borrow(book);
        // Then : RM-LOAN-04 (J+14), RM-LOAN-07 (stock et emprunt ensemble), RM-LOAN-09
        assertThat(loan.getMemberId()).isEqualTo(member);
        assertThat(loan.getBookId()).isEqualTo(book);
        assertThat(loan.getBorrowedOn()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(loan.getDueOn()).isEqualTo(LocalDate.of(2026, 12, 8));
        assertThat(loan.isActive()).isTrue();
        assertThat(loans.find(loan.getId())).isNotNull();
        assertThat(catalog.available(book)).isEqualTo(1);
    }

    @Test
    void borrow_quandAppel_alorsVerrouDuMembrePrisAvantLaLectureDesEmpruntsActifs() {
        // Given
        BookId book = catalog.givenBook("La Peste", 2);
        // When
        borrow(book);
        // Then : RM-LOAN-08, le verrou est la première opération, la réservation vient après la politique
        assertThat(journal).containsExactly("lockLoansOf", "loadSummaries", "loadActiveLoansOf", "reserve", "saveNew");
    }

    @Test
    void borrow_quandDernierExemplaire_alorsStockAZero() {
        // Given
        BookId book = catalog.givenBook("Dernier", 1);
        // When
        borrow(book);
        // Then
        assertThat(catalog.available(book)).isZero();
    }

    @Test
    void borrow_quandFuseauAsiaTokyoEtInstant20hUtc_alorsDateLocaleDuLendemain() {
        // Given : 2026-11-24T20:00Z = 2026-11-25 05:00 à Tokyo (RM-LOAN-29)
        Clock tokyo = Clock.fixed(Instant.parse("2026-11-24T20:00:00Z"), ZoneId.of("Asia/Tokyo"));
        BookId book = catalog.givenBook("Livre", 1);
        // When
        Loan loan = serviceWith(tokyo).borrow(new BorrowBookCommand(member, book));
        // Then
        assertThat(loan.getBorrowedOn()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(loan.getDueOn()).isEqualTo(LocalDate.of(2026, 12, 9));
    }

    @Test
    void borrow_quandMemeInstantEtFuseauDakar_alorsDateDuJour() {
        // Given : le même instant à Dakar (UTC+0) reste le 24
        Clock dakar = Clock.fixed(Instant.parse("2026-11-24T20:00:00Z"), DAKAR);
        BookId book = catalog.givenBook("Livre", 1);
        // When
        Loan loan = serviceWith(dakar).borrow(new BorrowBookCommand(member, book));
        // Then
        assertThat(loan.getBorrowedOn()).isEqualTo(LocalDate.of(2026, 11, 24));
    }

    @Test
    void borrow_quandSucces_alorsLogInfoAvecIdentifiantsSansDonneePersonnelle() {
        // Given
        BookId book = catalog.givenBook("Livre", 1);
        // When
        Loan loan = borrow(book);
        // Then : RM-LOAN-20
        assertThat(logs.list).hasSize(1);
        ILoggingEvent event = logs.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains(loan.getId().value().toString(),
                member.value().toString(), book.value().toString()).doesNotContain("@");
    }

    // ---- RM-LOAN-28 : variantes autour du même livre

    @Test
    void borrow_quandMemeLivreRenduPuisReemprunte_alorsAccepte() {
        // Given : un emprunt rendu du même livre
        BookId book = catalog.givenBook("La Peste", 2);
        givenReturnedLoan(member, book);
        // When
        Loan loan = borrow(book);
        // Then
        assertThat(loan.isActive()).isTrue();
        assertThat(loans.all()).hasSize(2);
    }

    @Test
    void borrow_quandAutreMembreAEmprunteLeMemeLivre_alorsAccepte() {
        // Given : un autre membre a déjà ce livre (2 exemplaires, 1 disponible)
        BookId book = catalog.givenBook("La Peste", 1);
        givenActiveLoan(new MemberId(UUID.randomUUID()), book, 7);
        // When
        Loan loan = borrow(book);
        // Then
        assertThat(loan.getMemberId()).isEqualTo(member);
        assertThat(catalog.available(book)).isZero();
    }

    @Test
    void borrow_quandDeuxActifsEtUnRenduLeJourMeme_alorsTroisiemeAccepte() {
        // Given : RM-LOAN-02, un emprunt rendu ne compte pas
        givenActiveLoan(member, catalog.givenBook("A", 1), 7);
        givenActiveLoan(member, catalog.givenBook("B", 1), 7);
        givenReturnedLoan(member, catalog.givenBook("C", 1));
        BookId target = catalog.givenBook("D", 1);
        // When
        Loan loan = borrow(target);
        // Then
        assertThat(loan.getBookId()).isEqualTo(target);
    }

    @Test
    void borrow_quandEcheanceAujourdhui_alorsPasEnRetardEtAccepte() {
        // Given : RM-LOAN-05, le jour de l'échéance n'est pas un retard
        givenActiveLoan(member, catalog.givenBook("A", 1), 0);
        BookId target = catalog.givenBook("B", 1);
        // When / Then
        assertThat(borrow(target).isActive()).isTrue();
    }

    // ---- refus : aucun effet

    @Test
    void borrow_quandTroisEmpruntsActifs_alorsLoanLimitReachedSansReservation() {
        // Given
        for (int i = 0; i < 3; i++) {
            givenActiveLoan(member, catalog.givenBook("Livre " + i, 1), 7);
        }
        BookId target = catalog.givenBook("Quatrième", 2);
        // When / Then : RM-LOAN-02
        assertThatThrownBy(() -> borrow(target)).isInstanceOf(LoanLimitReachedException.class);
        assertThat(catalog.reserveCalls()).isZero();
        assertNothingChanged(target, 2, 3);
    }

    @Test
    void borrow_quandMembreEnRetard_alorsMemberHasOverdueLoanSansReservation() {
        // Given : échéance hier
        givenActiveLoan(member, catalog.givenBook("En retard", 1), -1);
        BookId target = catalog.givenBook("Cible", 2);
        // When / Then : RM-LOAN-03
        assertThatThrownBy(() -> borrow(target)).isInstanceOf(MemberHasOverdueLoanException.class);
        assertThat(catalog.reserveCalls()).isZero();
        assertNothingChanged(target, 2, 1);
    }

    @Test
    void borrow_quandLivreDejaEmprunte_alorsBookAlreadyBorrowedSansReservation() {
        // Given
        BookId target = catalog.givenBook("La Peste", 2);
        givenActiveLoan(member, target, 7);
        // When / Then : RM-LOAN-28
        assertThatThrownBy(() -> borrow(target)).isInstanceOf(BookAlreadyBorrowedException.class);
        assertThat(catalog.reserveCalls()).isZero();
        assertNothingChanged(target, 2, 1);
    }

    @Test
    void borrow_quandAucunExemplaireDisponible_alorsBookNotAvailableEtAucunEmprunt() {
        // Given : RM-LOAN-01
        BookId target = catalog.givenBook("Épuisé", 0);
        // When / Then
        assertThatThrownBy(() -> borrow(target)).isInstanceOf(BookNotAvailableException.class)
                .extracting("code").isEqualTo("BOOK_NOT_AVAILABLE");
        assertThat(catalog.reserveCalls()).isEqualTo(1);
        assertNothingChanged(target, 0, 0);
    }

    @Test
    void borrow_quandLivreInconnu_alorsBookNotFoundSansLectureNiReservation() {
        // Given
        BookId unknown = new BookId(UUID.randomUUID());
        // When / Then
        assertThatThrownBy(() -> borrow(unknown)).isInstanceOf(BookNotFoundException.class)
                .extracting("code").isEqualTo("BOOK_NOT_FOUND");
        assertThat(journal).containsExactly("lockLoansOf", "loadSummaries");
    }

    @Test
    void borrow_quandLivreDisparuEntreLExistenceEtLaReservation_alorsBookNotFoundEtAucunEmprunt() {
        // Given : le catalogue répond « existe » puis « introuvable » à la réservation
        BookId book = catalog.givenBook("Fantôme", 1);
        catalog.forceReservation(CopyReservation.BOOK_NOT_FOUND);
        // When / Then
        assertThatThrownBy(() -> borrow(book)).isInstanceOf(BookNotFoundException.class);
        assertThat(loans.saveNewCalls()).isZero();
    }

    @Test
    void borrow_quandCourseDuDernierExemplairePerdue_alorsBookNotAvailableEtAucunEmprunt() {
        // Given : le stock semblait disponible mais la décrémentation conditionnelle a perdu la course
        BookId book = catalog.givenBook("Dernier", 1);
        catalog.forceReservation(CopyReservation.NO_COPY_AVAILABLE);
        // When / Then
        assertThatThrownBy(() -> borrow(book)).isInstanceOf(BookNotAvailableException.class);
        assertThat(loans.saveNewCalls()).isZero();
    }

    @Test
    void borrow_quandEnregistrementRefuseParIndexUnique_alorsBookAlreadyBorrowedPropagee() {
        // Given : filet de sécurité de la base (deux requêtes simultanées du même membre pour le même livre)
        BookId book = catalog.givenBook("La Peste", 5);
        loans.failOnSaveNew(new BookAlreadyBorrowedException());
        // When / Then : l'exception remonte, la transaction (non simulée ici) annule la réservation
        assertThatThrownBy(() -> borrow(book)).isInstanceOf(BookAlreadyBorrowedException.class);
        assertThat(loans.all()).isEmpty();
    }

    @Test
    void borrow_quandErreurTechniqueALEnregistrement_alorsExceptionPropagee() {
        // Given
        BookId book = catalog.givenBook("Livre", 1);
        loans.failOnSaveNew(new IllegalStateException("panne SQL"));
        // When / Then : RM-LOAN-07, l'exception remonte pour déclencher le rollback de la transaction du use case
        assertThatThrownBy(() -> borrow(book)).isInstanceOf(IllegalStateException.class).hasMessage("panne SQL");
        assertThat(loans.all()).isEmpty();
    }

    // ---- RM-LOAN-06 : ordre d'évaluation, la réservation n'a lieu qu'après les lignes 1 à 4

    record Case(String name, boolean unknownBook, boolean overdue, int activeLoans, boolean alreadyBorrowed,
                int stock, Class<? extends RuntimeException> expected, boolean reserveCalled) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Case> evaluationOrder() {
        return List.of(
                new Case("livre inconnu, membre en retard", true, true, 1, false, 0,
                        BookNotFoundException.class, false),
                new Case("retard, 3 actifs, déjà emprunté, 0 exemplaire", false, true, 3, true, 0,
                        MemberHasOverdueLoanException.class, false),
                new Case("aucun retard, 3 actifs dont ce livre, 0 exemplaire", false, false, 3, true, 0,
                        LoanLimitReachedException.class, false),
                new Case("aucun retard, 2 actifs dont ce livre, 0 exemplaire", false, false, 2, true, 0,
                        BookAlreadyBorrowedException.class, false),
                new Case("aucun retard, moins de 3 emprunts, livre non emprunté, 0 exemplaire", false, false, 1, false, 0,
                        BookNotAvailableException.class, true));
    }

    @ParameterizedTest
    @MethodSource("evaluationOrder")
    void borrow_quandPlusieursReglesViolees_alorsLaPremiereDeLOrdreDetermineLeCode(Case scenario) {
        // Given
        BookId target = scenario.unknownBook() ? new BookId(UUID.randomUUID())
                : catalog.givenBook("Cible", scenario.stock());
        int others = scenario.activeLoans() - (scenario.alreadyBorrowed() ? 1 : 0);
        for (int i = 0; i < others; i++) {
            givenActiveLoan(member, catalog.givenBook("Autre " + i, 1), scenario.overdue() && i == 0 ? -1 : 7);
        }
        if (scenario.alreadyBorrowed()) {
            givenActiveLoan(member, target, scenario.overdue() && others == 0 ? -1 : 7);
        }
        int loansBefore = loans.all().size();
        // When / Then
        assertThatThrownBy(() -> borrow(target)).isInstanceOf(scenario.expected());
        assertThat(catalog.reserveCalls() > 0).isEqualTo(scenario.reserveCalled());
        assertThat(loans.all()).hasSize(loansBefore);
        assertThat(loans.saveNewCalls()).isZero();
    }

    // ---- commande

    @Test
    void command_quandMembreOuLivreNul_alorsNullPointerException() {
        // When / Then
        assertThatThrownBy(() -> new BorrowBookCommand(null, new BookId(UUID.randomUUID())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BorrowBookCommand(member, null)).isInstanceOf(NullPointerException.class);
    }
}
