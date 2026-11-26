package com.example.library.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration;
import com.example.library.RedisTestcontainersConfiguration.RedisTestCache;
import com.example.library.loan.application.port.in.BorrowBookCommand;
import com.example.library.loan.application.port.in.BorrowBookUseCase;
import com.example.library.loan.application.port.in.ReturnLoanCommand;
import com.example.library.loan.application.port.in.ReturnLoanUseCase;
import com.example.library.loan.application.port.out.SaveLoanPort;
import com.example.library.loan.domain.exception.BookAlreadyBorrowedException;
import com.example.library.loan.domain.exception.BookNotAvailableException;
import com.example.library.loan.domain.exception.LoanAlreadyReturnedException;
import com.example.library.loan.domain.exception.LoanLimitReachedException;
import com.example.library.loan.domain.model.BookId;
import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.LoanId;
import com.example.library.loan.domain.model.MemberId;
import com.example.library.loan.support.FixedClockTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Concurrence et atomicité (RM-LOAN-07, 08, 11, 31) avec PostgreSQL et Redis réels. Les threads appellent les ports
 * d'entrée (use cases) et sont lâchés ensemble par un {@link CyclicBarrier} : chaque appel ouvre sa propre transaction.
 */
@SpringBootTest
@Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class,
        FixedClockTestConfiguration.class})
class LoanConcurrencyIntegrationTest {

    private static final String CACHE_KEY = "library:catalog:available-books:v1";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    @Autowired
    private BorrowBookUseCase borrowBookUseCase;

    @Autowired
    private ReturnLoanUseCase returnLoanUseCase;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisTestCache redisTestCache;

    /** Espionne l'unique bean qui implémente SaveLoanPort (l'adapter de persistance, package-private). */
    @MockitoSpyBean
    private SaveLoanPort saveLoanPort;

    private int sequence;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM loan");
        jdbc.update("DELETE FROM book");
        redisTestCache.flushAll();
    }

    // ------------------------------------------------------------------ aides

    private UUID givenBook(int total, int available) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO book (id, isbn, title, author, total_copies, available_copies, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0)",
                id, "978400000%04d".formatted(sequence++), "Livre " + sequence, "Auteur", total, available);
        return id;
    }

    private UUID givenActiveLoan(MemberId member, UUID book) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO loan (id, member_id, book_id, borrowed_on, due_on) VALUES (?, ?, ?, ?, ?)",
                id, member.value(), book, TODAY.minusDays(1), TODAY.plusDays(13));
        return id;
    }

    private static MemberId newMember() {
        return new MemberId(UUID.randomUUID());
    }

    private int available(UUID book) {
        return jdbc.queryForObject("SELECT available_copies FROM book WHERE id = ?", Integer.class, book);
    }

    private int totalLoans() {
        return jdbc.queryForObject("SELECT count(*) FROM loan", Integer.class);
    }

    private int activeLoans(MemberId member) {
        return jdbc.queryForObject("SELECT count(*) FROM loan WHERE member_id = ? AND returned_on IS NULL",
                Integer.class, member.value());
    }

    private int activeLoansOfBook(UUID book) {
        return jdbc.queryForObject("SELECT count(*) FROM loan WHERE book_id = ? AND returned_on IS NULL",
                Integer.class, book);
    }

    /** Résultat d'un appel concurrent : la valeur renvoyée, ou l'exception levée. */
    private record Outcome(Object value, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }

    /** Lance les tâches ensemble (barrière) et attend toutes les issues. */
    private List<Outcome> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            CyclicBarrier barrier = new CyclicBarrier(tasks.size());
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        return new Outcome(task.call(), null);
                    } catch (Throwable error) {
                        return new Outcome(null, error);
                    }
                }));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                try {
                    outcomes.add(future.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    throw new AssertionError("Thread de test en échec", e.getCause());
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Object> borrowTask(MemberId member, UUID book) {
        return () -> borrowBookUseCase.borrow(new BorrowBookCommand(member, new BookId(book)));
    }

    private Callable<Object> returnTask(MemberId member, UUID loan) {
        return () -> {
            returnLoanUseCase.giveBack(new ReturnLoanCommand(member, new LoanId(loan)));
            return "rendu";
        };
    }

    private static List<Outcome> failures(List<Outcome> outcomes) {
        return outcomes.stream().filter(outcome -> !outcome.succeeded()).toList();
    }

    private static long successes(List<Outcome> outcomes) {
        return outcomes.stream().filter(Outcome::succeeded).count();
    }

    // ------------------------------------------------------------------ RM-LOAN-08 : dernier exemplaire

    @RepeatedTest(5)
    void borrow_quandDernierExemplaireEtDeuxMembresSimultanes_alorsUnSeulSuccesEtStockAZero() throws Exception {
        // Given
        UUID book = givenBook(1, 1);
        // When
        List<Outcome> outcomes = runConcurrently(List.of(borrowTask(newMember(), book), borrowTask(newMember(), book)));
        // Then : un 201, un BOOK_NOT_AVAILABLE, jamais de stock négatif
        assertThat(successes(outcomes)).isEqualTo(1);
        assertThat(failures(outcomes)).singleElement()
                .satisfies(outcome -> assertThat(outcome.error()).isInstanceOf(BookNotAvailableException.class));
        assertThat(available(book)).isZero();
        assertThat(totalLoans()).isEqualTo(1);
    }

    @Test
    void borrow_quandHuitMembresSimultanesPourUnSeulExemplaire_alorsUnSeulSucces() throws Exception {
        // Given
        UUID book = givenBook(1, 1);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(borrowTask(newMember(), book));
        }
        // When
        List<Outcome> outcomes = runConcurrently(tasks);
        // Then
        assertThat(successes(outcomes)).isEqualTo(1);
        assertThat(failures(outcomes)).hasSize(7)
                .allSatisfy(outcome -> assertThat(outcome.error()).isInstanceOf(BookNotAvailableException.class));
        assertThat(available(book)).isZero();
        assertThat(activeLoansOfBook(book)).isEqualTo(1);
    }

    @Test
    void borrow_quandDeuxExemplairesEtCinqMembresSimultanes_alorsExactementDeuxSuccesEtCoherenceStockEmprunts()
            throws Exception {
        // Given
        UUID book = givenBook(2, 2);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(borrowTask(newMember(), book));
        }
        // When
        List<Outcome> outcomes = runConcurrently(tasks);
        // Then : stock = total - emprunts actifs, jamais négatif
        assertThat(successes(outcomes)).isEqualTo(2);
        assertThat(available(book)).isZero();
        assertThat(activeLoansOfBook(book)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ RM-LOAN-28 : même membre, même livre

    @RepeatedTest(5)
    void borrow_quandMemeMembreMemeLivreDeuxRequetesSimultanees_alorsUnSuccesEtUnBookAlreadyBorrowed() throws Exception {
        // Given
        MemberId member = newMember();
        UUID book = givenBook(5, 5);
        // When
        List<Outcome> outcomes = runConcurrently(List.of(borrowTask(member, book), borrowTask(member, book)));
        // Then : availableCopies a diminué de 1 seulement
        assertThat(successes(outcomes)).isEqualTo(1);
        assertThat(failures(outcomes)).singleElement()
                .satisfies(outcome -> assertThat(outcome.error()).isInstanceOf(BookAlreadyBorrowedException.class));
        assertThat(available(book)).isEqualTo(4);
        assertThat(activeLoans(member)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ RM-LOAN-08 : limite de 3

    @RepeatedTest(5)
    void borrow_quandDeuxActifsEtDeuxEmpruntsSimultanesDeLivresDifferents_alorsExactementTroisActifs() throws Exception {
        // Given
        MemberId member = newMember();
        givenActiveLoan(member, givenBook(1, 0));
        givenActiveLoan(member, givenBook(1, 0));
        UUID first = givenBook(2, 2);
        UUID second = givenBook(2, 2);
        // When
        List<Outcome> outcomes = runConcurrently(List.of(borrowTask(member, first), borrowTask(member, second)));
        // Then : un succès, un LOAN_LIMIT_REACHED, trois actifs exactement, un seul stock décrémenté
        assertThat(successes(outcomes)).isEqualTo(1);
        assertThat(failures(outcomes)).singleElement()
                .satisfies(outcome -> assertThat(outcome.error()).isInstanceOf(LoanLimitReachedException.class));
        assertThat(activeLoans(member)).isEqualTo(3);
        assertThat(available(first) + available(second)).isEqualTo(3);
    }

    @Test
    void borrow_quandAucunActifEtSixEmpruntsSimultanesDeLivresDifferents_alorsTroisActifsAuPlus() throws Exception {
        // Given
        MemberId member = newMember();
        List<UUID> books = new ArrayList<>();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID book = givenBook(1, 1);
            books.add(book);
            tasks.add(borrowTask(member, book));
        }
        // When
        List<Outcome> outcomes = runConcurrently(tasks);
        // Then
        assertThat(successes(outcomes)).isEqualTo(3);
        assertThat(failures(outcomes)).hasSize(3)
                .allSatisfy(outcome -> assertThat(outcome.error()).isInstanceOf(LoanLimitReachedException.class));
        assertThat(activeLoans(member)).isEqualTo(3);
        assertThat(books.stream().mapToInt(this::available).sum()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ RM-LOAN-11 : double retour

    @RepeatedTest(5)
    void giveBack_quandDeuxRetoursSimultanesDuMemeEmprunt_alorsUnSuccesEtStockIncrementeUneSeuleFois() throws Exception {
        // Given : un emprunt actif sur un livre à 1 exemplaire disponible sur 3
        MemberId member = newMember();
        UUID book = givenBook(3, 1);
        UUID loan = givenActiveLoan(member, book);
        // When
        List<Outcome> outcomes = runConcurrently(List.of(returnTask(member, loan), returnTask(member, loan)));
        // Then
        assertThat(successes(outcomes)).isEqualTo(1);
        assertThat(failures(outcomes)).singleElement()
                .satisfies(outcome -> assertThat(outcome.error()).isInstanceOf(LoanAlreadyReturnedException.class));
        assertThat(available(book)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT returned_on IS NOT NULL FROM loan WHERE id = ?", Boolean.class, loan))
                .isTrue();
    }

    @Test
    void giveBack_quandSixRetoursSimultanesDuMemeEmprunt_alorsUnSeulSucces() throws Exception {
        // Given
        MemberId member = newMember();
        UUID book = givenBook(3, 1);
        UUID loan = givenActiveLoan(member, book);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(returnTask(member, loan));
        }
        // When
        List<Outcome> outcomes = runConcurrently(tasks);
        // Then
        assertThat(successes(outcomes)).isEqualTo(1);
        assertThat(available(book)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ mélange emprunts et retours

    @RepeatedTest(3)
    void borrowEtGiveBack_quandSimultanesSurLeMemeLivre_alorsStockEgalTotalMoinsEmpruntsActifsSansInterblocage()
            throws Exception {
        // Given : 2 exemplaires, tous empruntés (par a et b) ; c et d tentent d'emprunter pendant que a et b rendent
        MemberId a = newMember();
        MemberId b = newMember();
        UUID book = givenBook(2, 0);
        UUID loanA = givenActiveLoan(a, book);
        UUID loanB = givenActiveLoan(b, book);
        // When
        List<Outcome> outcomes = runConcurrently(List.of(
                returnTask(a, loanA), returnTask(b, loanB), borrowTask(newMember(), book), borrowTask(newMember(), book)));
        // Then : quel que soit l'ordre, stock = total - emprunts actifs, bornes respectées, aucune erreur technique
        assertThat(failures(outcomes)).allSatisfy(outcome ->
                assertThat(outcome.error()).isInstanceOf(BookNotAvailableException.class));
        assertThat(available(book)).isBetween(0, 2);
        assertThat(available(book)).isEqualTo(2 - activeLoansOfBook(book));
    }

    // ------------------------------------------------------------------ RM-LOAN-07 : atomicité

    @Test
    void borrow_quandErreurTechniqueALEnregistrementDeLEmprunt_alorsStockInchangeEtAucunEmprunt() {
        // Given : le stock est décrémenté dans la même transaction, puis l'enregistrement de l'emprunt échoue
        MemberId member = newMember();
        UUID book = givenBook(2, 2);
        doThrow(new IllegalStateException("panne SQL simulée")).when(saveLoanPort).saveNew(any());
        // When / Then
        assertThatThrownBy(() -> borrowBookUseCase.borrow(new BorrowBookCommand(member, new BookId(book))))
                .isInstanceOf(IllegalStateException.class).hasMessage("panne SQL simulée");
        assertThat(available(book)).isEqualTo(2);
        assertThat(totalLoans()).isZero();
    }

    @Test
    void borrow_quandErreurTechniqueALEnregistrement_alorsCleDuCacheNonInvalidee() {
        // Given : une valeur en cache ; le rollback ne doit rien invalider
        MemberId member = newMember();
        UUID book = givenBook(1, 1);
        redis.opsForValue().set(CACHE_KEY, "[]");
        doThrow(new IllegalStateException("panne SQL simulée")).when(saveLoanPort).saveNew(any());
        // When
        assertThatThrownBy(() -> borrowBookUseCase.borrow(new BorrowBookCommand(member, new BookId(book))))
                .isInstanceOf(IllegalStateException.class);
        // Then
        assertThat(redis.hasKey(CACHE_KEY)).isTrue();
    }

    @Test
    void borrow_quandSucces_alorsCleDuCacheInvalideeApresLeCommit() {
        // Given
        MemberId member = newMember();
        UUID book = givenBook(1, 1);
        redis.opsForValue().set(CACHE_KEY, "[]");
        // When
        Loan loan = borrowBookUseCase.borrow(new BorrowBookCommand(member, new BookId(book)));
        // Then
        assertThat(loan.getMemberId()).isEqualTo(member);
        assertThat(redis.hasKey(CACHE_KEY)).isFalse();
    }

    @Test
    void giveBack_quandErreurTechniqueALEnregistrementDuRetour_alorsStockEtEmpruntInchanges() {
        // Given : l'emprunt est actif et le stock à 0 ; l'enregistrement du retour échoue
        MemberId member = newMember();
        UUID book = givenBook(1, 0);
        UUID loan = givenActiveLoan(member, book);
        doThrow(new IllegalStateException("panne SQL simulée")).when(saveLoanPort).saveReturned(any());
        // When / Then
        assertThatThrownBy(() -> returnLoanUseCase.giveBack(new ReturnLoanCommand(member, new LoanId(loan))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(available(book)).isZero();
        assertThat(jdbc.queryForObject("SELECT returned_on IS NULL FROM loan WHERE id = ?", Boolean.class, loan))
                .isTrue();
    }

    @Test
    void borrow_quandVerrouDuMembre_alorsLeUseCaseSExecuteDansUneTransaction() {
        // Given / When / Then : le verrou consultatif de transaction n'a de sens que dans la transaction du use case
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(borrowBookUseCase)).isTrue();
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(returnLoanUseCase)).isTrue();
    }

    // ------------------------------------------------------------------ attente déterministe sur le verrou du membre

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private com.example.library.loan.application.port.out.LockMemberLoansPort lockMemberLoansPort;

    private int waitingAdvisoryLocks() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted",
                Integer.class);
    }

    /** Une transaction prend le verrou du membre, exécute {@code whileHolding}, puis attend le latch avant de valider. */
    private java.util.concurrent.CompletableFuture<Void> holdMemberLock(MemberId member,
            java.util.concurrent.CountDownLatch locked, java.util.concurrent.CountDownLatch release,
            Runnable whileHolding) {
        return java.util.concurrent.CompletableFuture.runAsync(() ->
                new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            lockMemberLoansPort.lockLoansOf(member);
                            whileHolding.run();
                            locked.countDown();
                            try {
                                release.await(60, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }));
    }

    private Future<Outcome> borrowInBackground(ExecutorService executor, MemberId member, UUID book) {
        return executor.submit(() -> {
            try {
                return new Outcome(borrowTask(member, book).call(), null);
            } catch (Throwable error) {
                return new Outcome(null, error);
            }
        });
    }

    /** Attend (au plus 4,5 s) qu'une session soit réellement bloquée sur un verrou consultatif. */
    private void awaitBlockedSession() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(4500);
        while (waitingAdvisoryLocks() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        org.assertj.core.api.Assertions.assertThat(waitingAdvisoryLocks())
                .as("aucune session n'est restée bloquée sur le verrou consultatif du membre (attente maximale 4,5 s)")
                .isPositive();
    }

    @Test
    void borrow_quandVerrouDuMembreTenuParUneTransaction_alorsLeSecondEmpruntAttendPuisVoitLEtatValide()
            throws Exception {
        // Given : le membre a 2 actifs ; la transaction A tient le verrou et ajoute un 3e emprunt (non validé)
        MemberId member = newMember();
        givenActiveLoan(member, givenBook(1, 0));
        givenActiveLoan(member, givenBook(1, 0));
        UUID third = givenBook(1, 0);
        UUID target = givenBook(2, 2);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holder = holdMemberLock(member, locked, release, () -> jdbc.update(
                "INSERT INTO loan (id, member_id, book_id, borrowed_on, due_on) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), member.value(), third, TODAY.minusDays(1), TODAY.plusDays(13)));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // When : B tente d'emprunter pendant que A tient le verrou
            Future<Outcome> second = borrowInBackground(executor, member, target);
            // Then : B est réellement bloqué sur le verrou (pg_locks) et n'a pas terminé
            awaitBlockedSession();
            assertThat(waitingAdvisoryLocks()).isEqualTo(1);
            Thread.sleep(500);
            assertThat(second.isDone()).isFalse();
            // When : A valide (le 3e emprunt devient visible) et libère le verrou
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            // Then : B se réveille et voit l'état validé de A
            Outcome outcome = second.get(10, TimeUnit.SECONDS);
            assertThat(outcome.error()).isInstanceOf(LoanLimitReachedException.class);
            assertThat(available(target)).isEqualTo(2);
            assertThat(activeLoans(member)).isEqualTo(3);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void borrow_quandVerrouDuMembreTenuSansChangerLEtat_alorsLeSecondEmpruntAttendPuisReussit() throws Exception {
        // Given
        MemberId member = newMember();
        UUID target = givenBook(2, 2);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holder = holdMemberLock(member, locked, release, () -> { });
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Outcome> second = borrowInBackground(executor, member, target);
            // When / Then : bloqué tant que le verrou est tenu, rien n'est encore écrit
            awaitBlockedSession();
            assertThat(waitingAdvisoryLocks()).isEqualTo(1);
            assertThat(second.isDone()).isFalse();
            assertThat(totalLoans()).isZero();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS).succeeded()).isTrue();
            assertThat(available(target)).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void borrow_quandVerrouTenuPourUnAutreMembre_alorsEmpruntNonBloque() throws Exception {
        // Given : le verrou d'un membre n'en bloque pas un autre
        MemberId holderMember = newMember();
        MemberId other = newMember();
        UUID book = givenBook(1, 1);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holder = holdMemberLock(holderMember, locked, release, () -> { });
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            // When
            Loan loan = borrowBookUseCase.borrow(new BorrowBookCommand(other, new BookId(book)));
            // Then
            assertThat(loan.getMemberId()).isEqualTo(other);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }
}
