package com.example.library.loan.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.loan.domain.exception.BookAlreadyBorrowedException;
import com.example.library.loan.domain.exception.LoanAlreadyReturnedException;
import com.example.library.loan.domain.model.BookId;
import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.LoanId;
import com.example.library.loan.domain.model.MemberId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistance réelle des emprunts (PostgreSQL via Testcontainers, schéma Flyway V3), avec un fuseau JVM
 * {@code Asia/Tokyo} pour prouver que les dates relues sont exactes (risque R5, {@code hibernate.jdbc.time_zone=UTC}).
 * Couvre RM-01 à RM-03, RM-05 (colonnes), RM-LOAN-08 (verrou), RM-LOAN-11 (UPDATE conditionnel), RM-LOAN-13, 28.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestcontainersConfiguration.class, LoanPersistenceAdapter.class, LoanPersistenceMapper.class})
class LoanPersistenceAdapterTest {

    private static final TimeZone INITIAL_ZONE = TimeZone.getDefault();

    private static final MemberId MEMBER = new MemberId(UUID.randomUUID());
    private static final MemberId OTHER = new MemberId(UUID.randomUUID());

    @Autowired
    private LoanPersistenceAdapter adapter;

    @Autowired
    private LoanJpaRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Avant la création du contexte Spring (donc de la connexion) : tout le test tourne sous un fuseau JVM décalé. */
    @BeforeAll
    static void jvmInTokyo() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
    }

    @AfterAll
    static void restoreJvmZone() {
        TimeZone.setDefault(INITIAL_ZONE);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM loan");
    }

    private static BookId newBook() {
        return new BookId(UUID.randomUUID());
    }

    private static Loan loan(MemberId member, BookId book, LocalDate borrowedOn, LocalDate dueOn, LocalDate returnedOn) {
        return Loan.restore(LoanId.newId(), member, book, borrowedOn, dueOn, returnedOn);
    }

    private static Loan open(MemberId member, BookId book, LocalDate today) {
        return Loan.open(member, book, today);
    }

    // ---- aller-retour et dates exactes

    @Test
    void saveNew_quandEmpruntNeuf_alorsRelectureIdentiqueAvecDatesExactesSousFuseauJvmTokyo() {
        // Given : JVM en Asia/Tokyo, emprunt du 2026-11-24 (échéance 2026-12-08)
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Tokyo");
        BookId book = newBook();
        Loan saved = open(MEMBER, book, LocalDate.of(2026, 11, 24));
        // When
        adapter.saveNew(saved);
        Loan reloaded = adapter.loadActiveLoansOf(MEMBER).get(0);
        // Then : domaine et colonnes DATE brutes sont identiques
        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        assertThat(reloaded.getMemberId()).isEqualTo(MEMBER);
        assertThat(reloaded.getBookId()).isEqualTo(book);
        assertThat(reloaded.getBorrowedOn()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(reloaded.getDueOn()).isEqualTo(LocalDate.of(2026, 12, 8));
        assertThat(reloaded.getReturnedOn()).isNull();
        assertThat(jdbc.queryForObject("SELECT to_char(borrowed_on, 'YYYY-MM-DD') FROM loan", String.class))
                .isEqualTo("2026-11-24");
        assertThat(jdbc.queryForObject("SELECT to_char(due_on, 'YYYY-MM-DD') FROM loan", String.class))
                .isEqualTo("2026-12-08");
    }

    @Test
    void saveNew_quandDatesAuxLimitesDeLAnneeSousFuseauJvmTokyo_alorsAucunDecalageDUnJour() {
        // Given : le 1er janvier est la date la plus exposée à un décalage de fuseau
        Loan saved = open(MEMBER, newBook(), LocalDate.of(2027, 1, 1));
        // When
        adapter.saveNew(saved);
        Loan reloaded = adapter.loadActiveLoansOf(MEMBER).get(0);
        // Then
        assertThat(reloaded.getBorrowedOn()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(reloaded.getDueOn()).isEqualTo(LocalDate.of(2027, 1, 15));
    }

    @Test
    void saveReturned_quandEmpruntActif_alorsDateDeRetourExacteEtEmpruntPlusActif() {
        // Given
        Loan saved = open(MEMBER, newBook(), LocalDate.of(2026, 11, 24));
        adapter.saveNew(saved);
        saved.giveBack(LocalDate.of(2026, 11, 30));
        // When
        adapter.saveReturned(saved);
        // Then
        assertThat(jdbc.queryForObject("SELECT to_char(returned_on, 'YYYY-MM-DD') FROM loan", String.class))
                .isEqualTo("2026-11-30");
        assertThat(adapter.loadActiveLoansOf(MEMBER)).isEmpty();
    }

    // ---- lecture des emprunts actifs

    @Test
    void loadActiveLoansOf_quandEmpruntsActifsRendusEtAutreMembre_alorsSeulsLesActifsDuMembreTriesParEcheance() {
        // Given
        Loan late = loan(MEMBER, newBook(), LocalDate.of(2026, 11, 20), LocalDate.of(2026, 12, 18), null);
        Loan early = loan(MEMBER, newBook(), LocalDate.of(2026, 11, 10), LocalDate.of(2026, 12, 2), null);
        Loan returned = loan(MEMBER, newBook(), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15),
                LocalDate.of(2026, 11, 10));
        Loan others = loan(OTHER, newBook(), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 19), null);
        List.of(late, early, returned, others).forEach(adapter::saveNew);
        // When
        List<Loan> active = adapter.loadActiveLoansOf(MEMBER);
        // Then
        assertThat(active).extracting(Loan::getId).containsExactly(early.getId(), late.getId());
    }

    @Test
    void loadActiveLoansOf_quandMemeEcheance_alorsOrdreIdentiqueADisplayOrder() {
        // Given : identifiants dont l'ordre signé de UUID.compareTo diffère de celui de PostgreSQL
        LocalDate due = LocalDate.of(2026, 12, 8);
        Loan high = Loan.restore(new LoanId(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")), MEMBER,
                newBook(), due.minusDays(14), due, null);
        Loan low = Loan.restore(new LoanId(UUID.fromString("00000000-0000-0000-0000-000000000001")), MEMBER,
                newBook(), due.minusDays(14), due, null);
        Loan middle = Loan.restore(new LoanId(UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")), MEMBER,
                newBook(), due.minusDays(14), due, null);
        List.of(high, middle, low).forEach(adapter::saveNew);
        // When
        List<Loan> fromDatabase = adapter.loadActiveLoansOf(MEMBER);
        // Then : le tri SQL (ORDER BY due_on, id) correspond au tri du domaine
        assertThat(fromDatabase).extracting(Loan::getId)
                .containsExactlyElementsOf(fromDatabase.stream().sorted(Loan.DISPLAY_ORDER).map(Loan::getId).toList())
                .containsExactly(low.getId(), middle.getId(), high.getId());
    }

    @Test
    void loadActiveLoansOf_quandAucunEmprunt_alorsListeVide() {
        // When / Then
        assertThat(adapter.loadActiveLoansOf(new MemberId(UUID.randomUUID()))).isEmpty();
    }

    // ---- RM-LOAN-28 : index unique partiel

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void saveNew_quandMemeMembreMemeLivreDejaActif_alorsBookAlreadyBorrowedEtUnSeulEmprunt() {
        // Given
        BookId book = newBook();
        adapter.saveNew(open(MEMBER, book, LocalDate.of(2026, 11, 24)));
        // When / Then : l'index unique est traduit en exception métier, sans nom de contrainte
        assertThatThrownBy(() -> adapter.saveNew(open(MEMBER, book, LocalDate.of(2026, 11, 25))))
                .isInstanceOf(BookAlreadyBorrowedException.class)
                .extracting("code").isEqualTo("BOOK_ALREADY_BORROWED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan", Integer.class)).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void saveNew_quandReempruntApresRetour_alorsAccepte() {
        // Given : un emprunt rendu du même livre
        BookId book = newBook();
        Loan first = open(MEMBER, book, LocalDate.of(2026, 11, 24));
        adapter.saveNew(first);
        first.giveBack(LocalDate.of(2026, 11, 26));
        inTransaction(() -> adapter.saveReturned(first));
        // When
        adapter.saveNew(open(MEMBER, book, LocalDate.of(2026, 11, 27)));
        // Then : l'index est partiel (WHERE returned_on IS NULL)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan", Integer.class)).isEqualTo(2);
        assertThat(adapter.loadActiveLoansOf(MEMBER)).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void saveNew_quandAutreMembreMemeLivre_alorsAccepte() {
        // Given
        BookId book = newBook();
        adapter.saveNew(open(MEMBER, book, LocalDate.of(2026, 11, 24)));
        // When
        adapter.saveNew(open(OTHER, book, LocalDate.of(2026, 11, 24)));
        // Then
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan", Integer.class)).isEqualTo(2);
    }

    // ---- RM-LOAN-11 : retour conditionnel

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void saveReturned_quandDejaRendu_alorsZeroLigneEtLoanAlreadyReturnedSansChangerLaDate() {
        // Given
        Loan loan = open(MEMBER, newBook(), LocalDate.of(2026, 11, 24));
        adapter.saveNew(loan);
        loan.giveBack(LocalDate.of(2026, 11, 25));
        inTransaction(() -> adapter.saveReturned(loan));
        Loan secondReturn = Loan.restore(loan.getId(), MEMBER, loan.getBookId(), loan.getBorrowedOn(), loan.getDueOn(),
                LocalDate.of(2026, 11, 30));
        // When / Then
        assertThatThrownBy(() -> inTransaction(() -> adapter.saveReturned(secondReturn)))
                .isInstanceOf(LoanAlreadyReturnedException.class);
        assertThat(jdbc.queryForObject("SELECT to_char(returned_on, 'YYYY-MM-DD') FROM loan", String.class))
                .isEqualTo("2026-11-25");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void saveReturned_quandEmpruntDUnAutreMembre_alorsAucuneLigneModifiee() {
        // Given
        Loan loan = open(MEMBER, newBook(), LocalDate.of(2026, 11, 24));
        adapter.saveNew(loan);
        Loan forged = Loan.restore(loan.getId(), OTHER, loan.getBookId(), loan.getBorrowedOn(), loan.getDueOn(),
                LocalDate.of(2026, 11, 25));
        // When / Then : la condition porte aussi sur member_id
        assertThatThrownBy(() -> inTransaction(() -> adapter.saveReturned(forged)))
                .isInstanceOf(LoanAlreadyReturnedException.class);
        assertThat(adapter.loadActiveLoansOf(MEMBER)).hasSize(1);
    }

    // ---- RM-LOAN-13 : lecture verrouillée

    @Test
    void loadForUpdate_quandEmpruntDuMembre_alorsPresentActifOuRendu() {
        // Given
        Loan active = open(MEMBER, newBook(), LocalDate.of(2026, 11, 24));
        Loan returned = loan(MEMBER, newBook(), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15),
                LocalDate.of(2026, 11, 5));
        adapter.saveNew(active);
        adapter.saveNew(returned);
        // When / Then
        assertThat(adapter.loadForUpdate(active.getId(), MEMBER)).get().extracting(Loan::isActive).isEqualTo(true);
        Optional<Loan> reloadedReturned = adapter.loadForUpdate(returned.getId(), MEMBER);
        assertThat(reloadedReturned).isPresent();
        assertThat(reloadedReturned.get().getReturnedOn()).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void loadForUpdate_quandEmpruntDUnAutreMembre_alorsVide() {
        // Given
        Loan others = open(OTHER, newBook(), LocalDate.of(2026, 11, 24));
        adapter.saveNew(others);
        // When / Then : même réponse qu'un emprunt inconnu
        assertThat(adapter.loadForUpdate(others.getId(), MEMBER)).isEmpty();
    }

    @Test
    void loadForUpdate_quandEmpruntInconnu_alorsVide() {
        // When / Then
        assertThat(adapter.loadForUpdate(LoanId.newId(), MEMBER)).isEmpty();
    }

    @Test
    void findByIdAndMemberId_quandAutreMembre_alorsVideEtQuandBonMembrePresent() {
        // Given
        Loan loan = open(MEMBER, newBook(), LocalDate.of(2026, 11, 24));
        adapter.saveNew(loan);
        // When / Then
        assertThat(repository.findByIdAndMemberId(loan.getId().value(), OTHER.value())).isEmpty();
        assertThat(repository.findByIdAndMemberId(loan.getId().value(), MEMBER.value())).isPresent();
    }

    // ---- RM-LOAN-08 : verrou consultatif par membre

    @Test
    void lockLoansOf_quandTransactionEnCours_alorsVerrouTenuPourCeMembreSeulementEtDelaiDeVerrouDeCinqSecondes()
            throws Exception {
        // Given : le test s'exécute dans une transaction (DataJpaTest)
        // When : le verrou du membre est pris dans cette transaction
        adapter.lockLoansOf(MEMBER);
        // Then : une autre session ne peut pas prendre le même verrou, mais peut prendre celui d'un autre membre
        assertThat(canLockFromAnotherSession(MEMBER)).isFalse();
        assertThat(canLockFromAnotherSession(OTHER)).isTrue();
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lockLoansOf_quandTransactionTerminee_alorsVerrouLibere() throws Exception {
        // Given : transaction explicite, ouverte puis validée
        inTransaction(() -> adapter.lockLoansOf(MEMBER));
        // When / Then : libéré automatiquement au commit
        assertThat(canLockFromAnotherSession(MEMBER)).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lockLoansOf_quandHorsTransaction_alorsIllegalTransactionStateException() {
        // Given : aucune transaction (propagation MANDATORY), le verrou de transaction n'aurait aucun effet
        // When / Then
        assertThatThrownBy(() -> adapter.lockLoansOf(MEMBER))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    /** Tente de prendre le même verrou consultatif depuis une autre connexion (autre thread, hors transaction du test). */
    private boolean canLockFromAnotherSession(MemberId member) throws Exception {
        return CompletableFuture.supplyAsync(() -> new JdbcTemplate(dataSource).queryForObject(
                        "SELECT pg_try_advisory_xact_lock(?, hashtext(CAST(? AS text)))", Boolean.class,
                        LoanPersistenceAdapter.ADVISORY_LOCK_NAMESPACE, member.value().toString()))
                .get(10, TimeUnit.SECONDS);
    }

    // ---- schéma (V3)

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void migration_quandEcheanceAvantEmprunt_alorsRefuseParContrainteCheck() {
        // When / Then : ck_loan_due_on_not_before_borrowed_on
        assertThatThrownBy(() -> jdbc.update("INSERT INTO loan (id, member_id, book_id, borrowed_on, due_on)"
                        + " VALUES (?, ?, ?, DATE '2026-11-24', DATE '2026-11-23')",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_loan_due_on_not_before_borrowed_on");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void migration_quandRetourAvantEmprunt_alorsRefuseParContrainteCheck() {
        // When / Then : ck_loan_returned_on_not_before_borrowed_on
        assertThatThrownBy(() -> jdbc.update("INSERT INTO loan (id, member_id, book_id, borrowed_on, due_on, returned_on)"
                        + " VALUES (?, ?, ?, DATE '2026-11-24', DATE '2026-12-08', DATE '2026-11-23')",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_loan_returned_on_not_before_borrowed_on");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void saveNew_quandEcheanceAvantEmprunt_alorsDataIntegrityViolationRelanceeSansTraductionMetier() {
        // Given : l'adapter ne traduit que l'index unique, toute autre violation remonte telle quelle
        Loan inconsistent = loan(MEMBER, newBook(), LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 20), null);
        // When / Then
        assertThatThrownBy(() -> adapter.saveNew(inconsistent))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(BookAlreadyBorrowedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan", Integer.class)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void migration_quandPlusieursEmpruntsRendusDuMemeLivre_alorsAutorises() {
        // Given / When : deux emprunts rendus du même couple membre / livre
        UUID member = UUID.randomUUID();
        UUID book = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            jdbc.update("INSERT INTO loan (id, member_id, book_id, borrowed_on, due_on, returned_on)"
                    + " VALUES (?, ?, ?, DATE '2026-11-01', DATE '2026-11-15', DATE '2026-11-05')",
                    UUID.randomUUID(), member, book);
        }
        // Then : l'unicité ne porte que sur les emprunts actifs, l'historique est conservé
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan", Integer.class)).isEqualTo(2);
    }

    @Test
    void migration_quandTableLoan_alorsAucuneCleEtrangere() {
        // When
        Integer foreignKeys = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints"
                        + " WHERE table_name = 'loan' AND constraint_type = 'FOREIGN KEY'", Integer.class);
        // Then : member_id et book_id appartiennent à d'autres modules, pas de clé étrangère
        assertThat(foreignKeys).isZero();
    }
}
