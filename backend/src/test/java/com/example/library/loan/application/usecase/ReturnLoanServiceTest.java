package com.example.library.loan.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.library.loan.application.fake.FakeCatalog;
import com.example.library.loan.application.fake.InMemoryLoanRepository;
import com.example.library.loan.application.port.in.ReturnLoanCommand;
import com.example.library.loan.domain.exception.LoanAlreadyReturnedException;
import com.example.library.loan.domain.exception.LoanNotFoundException;
import com.example.library.loan.domain.model.BookId;
import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.LoanId;
import com.example.library.loan.domain.model.MemberId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Retour : RM-05 (RM-LOAN-10 à 13). Orchestration seulement ; la règle « pas deux fois » est dans {@code Loan}. */
class ReturnLoanServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneId.of("Africa/Dakar"));

    private final InMemoryLoanRepository loans = new InMemoryLoanRepository();
    private final FakeCatalog catalog = new FakeCatalog();
    private final ReturnLoanService service = new ReturnLoanService(loans, loans, catalog, CLOCK);
    private final MemberId member = new MemberId(UUID.randomUUID());

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(ReturnLoanService.class);
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

    private Loan givenLoan(MemberId owner, BookId book, int dueInDays, LocalDate returnedOn) {
        LocalDate dueOn = TODAY.plusDays(dueInDays);
        Loan loan = Loan.restore(LoanId.newId(), owner, book, dueOn.minusDays(14), dueOn, returnedOn);
        loans.givenLoan(loan);
        return loan;
    }

    private void giveBack(MemberId by, LoanId id) {
        service.giveBack(new ReturnLoanCommand(by, id));
    }

    @Test
    void giveBack_quandEmpruntActifDuMembre_alorsDateDuJourEtExemplaireRestitue() {
        // Given : un livre à 0 exemplaire disponible, emprunté par le membre
        BookId book = catalog.givenBook("La Peste", 0);
        Loan loan = givenLoan(member, book, 7, null);
        // When
        giveBack(member, loan.getId());
        // Then : RM-LOAN-10
        assertThat(loans.find(loan.getId()).getReturnedOn()).isEqualTo(TODAY);
        assertThat(catalog.available(book)).isEqualTo(1);
        assertThat(catalog.releaseCalls()).isEqualTo(1);
    }

    @Test
    void giveBack_quandRetourReussi_alorsLogInfoAvecIdentifiantsSansDonneePersonnelle() {
        // Given
        BookId book = catalog.givenBook("La Peste", 0);
        Loan loan = givenLoan(member, book, 7, null);
        // When
        giveBack(member, loan.getId());
        // Then : RM-LOAN-20
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(logs.list.get(0).getFormattedMessage())
                .contains(loan.getId().value().toString(), member.value().toString(), book.value().toString())
                .doesNotContain("@");
    }

    @Test
    void giveBack_quandEmpruntInconnu_alorsLoanNotFoundSansLiberation() {
        // Given
        BookId book = catalog.givenBook("Livre", 0);
        // When / Then : RM-LOAN-13
        assertThatThrownBy(() -> giveBack(member, LoanId.newId()))
                .isInstanceOf(LoanNotFoundException.class)
                .extracting("code").isEqualTo("LOAN_NOT_FOUND");
        assertThat(catalog.releaseCalls()).isZero();
        assertThat(catalog.available(book)).isZero();
    }

    @Test
    void giveBack_quandEmpruntDUnAutreMembre_alorsLoanNotFoundEtRienModifie() {
        // Given
        BookId book = catalog.givenBook("Livre", 0);
        Loan others = givenLoan(new MemberId(UUID.randomUUID()), book, 7, null);
        // When / Then : même réponse qu'un emprunt inconnu
        assertThatThrownBy(() -> giveBack(member, others.getId())).isInstanceOf(LoanNotFoundException.class);
        assertThat(loans.find(others.getId()).isActive()).isTrue();
        assertThat(loans.saveReturnedCalls()).isZero();
        assertThat(catalog.releaseCalls()).isZero();
        assertThat(catalog.available(book)).isZero();
    }

    @Test
    void giveBack_quandDejaRendu_alorsLoanAlreadyReturnedEtLiberationNonAppelee() {
        // Given
        BookId book = catalog.givenBook("Livre", 1);
        Loan returned = givenLoan(member, book, 7, TODAY.minusDays(1));
        // When / Then : RM-LOAN-11, aucun second incrément
        assertThatThrownBy(() -> giveBack(member, returned.getId()))
                .isInstanceOf(LoanAlreadyReturnedException.class)
                .extracting("code").isEqualTo("LOAN_ALREADY_RETURNED");
        assertThat(catalog.releaseCalls()).isZero();
        assertThat(catalog.available(book)).isEqualTo(1);
        assertThat(loans.find(returned.getId()).getReturnedOn()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void giveBack_quandRetourSimultanePerduALEnregistrement_alorsLoanAlreadyReturnedEtLiberationNonAppelee() {
        // Given : l'emprunt est actif à la lecture, mais l'UPDATE conditionnel ne modifie aucune ligne
        // (un autre retour l'a validé entre-temps) : saveReturned lève LoanAlreadyReturnedException
        BookId book = catalog.givenBook("Livre", 0);
        Loan loan = givenLoan(member, book, 7, null);
        InMemoryLoanRepository racing = new InMemoryLoanRepository() {
            @Override
            public java.util.Optional<Loan> loadForUpdate(LoanId loanId, MemberId memberId) {
                Loan seen = super.loadForUpdate(loanId, memberId).orElseThrow();
                // l'autre transaction rend l'emprunt juste après notre lecture
                Loan other = super.loadForUpdate(loanId, memberId).orElseThrow();
                other.giveBack(TODAY);
                super.saveReturned(other);
                return java.util.Optional.of(seen);
            }
        };
        racing.givenLoan(loan);
        ReturnLoanService racingService = new ReturnLoanService(racing, racing, catalog, CLOCK);
        // When / Then : RM-LOAN-11
        assertThatThrownBy(() -> racingService.giveBack(new ReturnLoanCommand(member, loan.getId())))
                .isInstanceOf(LoanAlreadyReturnedException.class);
        assertThat(catalog.releaseCalls()).isZero();
        assertThat(catalog.available(book)).isZero();
    }

    @Test
    void giveBack_quandEmpruntEnRetard_alorsRetourAccepte() {
        // Given : RM-LOAN-12
        BookId book = catalog.givenBook("Livre", 0);
        Loan late = givenLoan(member, book, -10, null);
        // When
        giveBack(member, late.getId());
        // Then
        assertThat(loans.find(late.getId()).getReturnedOn()).isEqualTo(TODAY);
        assertThat(catalog.available(book)).isEqualTo(1);
    }

    @Test
    void giveBack_quandLivreDisparuDuCatalogue_alorsRetourConserveEtWarn() {
        // Given : le livre a été supprimé du catalogue
        BookId book = catalog.givenBook("Disparu", 0);
        Loan loan = givenLoan(member, book, 7, null);
        catalog.removeBook(book);
        // When
        assertThatCode(() -> giveBack(member, loan.getId())).doesNotThrowAnyException();
        // Then : D3, retour enregistré, WARN sans donnée personnelle
        assertThat(loans.find(loan.getId()).getReturnedOn()).isEqualTo(TODAY);
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(loan.getId().value().toString()).doesNotContain("@");
        });
    }

    @Test
    void giveBack_quandAppel_alorsLectureVerrouilleeAvantEnregistrementPuisLiberation() {
        // Given
        java.util.List<String> journal = new java.util.ArrayList<>();
        InMemoryLoanRepository ordered = new InMemoryLoanRepository(journal);
        FakeCatalog orderedCatalog = new FakeCatalog(journal);
        BookId book = orderedCatalog.givenBook("Livre", 0);
        Loan loan = Loan.restore(LoanId.newId(), member, book, TODAY.minusDays(3), TODAY.plusDays(11), null);
        ordered.givenLoan(loan);
        // When
        new ReturnLoanService(ordered, ordered, orderedCatalog, CLOCK).giveBack(new ReturnLoanCommand(member, loan.getId()));
        // Then : ordre des verrous du plan (ligne emprunt, puis ligne livre)
        assertThat(journal).containsExactly("loadForUpdate", "saveReturned", "release");
    }

    @Test
    void command_quandMembreOuEmpruntNul_alorsNullPointerException() {
        // When / Then
        assertThatThrownBy(() -> new ReturnLoanCommand(null, LoanId.newId())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReturnLoanCommand(member, null)).isInstanceOf(NullPointerException.class);
    }
}
