package com.example.library.loan.application.fake;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.library.loan.application.port.out.LoadActiveLoansPort;
import com.example.library.loan.application.port.out.LoadMemberLoanForUpdatePort;
import com.example.library.loan.application.port.out.LockMemberLoansPort;
import com.example.library.loan.application.port.out.SaveLoanPort;
import com.example.library.loan.domain.exception.LoanAlreadyReturnedException;
import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.LoanId;
import com.example.library.loan.domain.model.MemberId;

/**
 * Fake en mémoire des ports de sortie de persistance des emprunts. Tient un journal d'appels (partageable avec
 * {@link FakeCatalog}) pour vérifier l'ordre d'orchestration.
 */
public class InMemoryLoanRepository
        implements LockMemberLoansPort, LoadActiveLoansPort, LoadMemberLoanForUpdatePort, SaveLoanPort {

    private final Map<LoanId, Loan> loans = new LinkedHashMap<>();
    private final List<String> journal;
    private RuntimeException failureOnSaveNew;
    private int saveNewCalls;
    private int saveReturnedCalls;

    public InMemoryLoanRepository() {
        this(new ArrayList<>());
    }

    public InMemoryLoanRepository(List<String> journal) {
        this.journal = journal;
    }

    /** Pré-charge un emprunt sans compter comme une sauvegarde du use case. */
    public void givenLoan(Loan loan) {
        loans.put(loan.getId(), copyOf(loan));
    }

    /** La prochaine sauvegarde d'un nouvel emprunt échoue avec cette exception (erreur technique ou index unique). */
    public void failOnSaveNew(RuntimeException failure) {
        this.failureOnSaveNew = failure;
    }

    public List<String> journal() {
        return journal;
    }

    public List<Loan> all() {
        return new ArrayList<>(loans.values());
    }

    public Loan find(LoanId id) {
        return loans.get(id);
    }

    public int saveNewCalls() {
        return saveNewCalls;
    }

    public int saveReturnedCalls() {
        return saveReturnedCalls;
    }

    @Override
    public void lockLoansOf(MemberId memberId) {
        journal.add("lockLoansOf");
    }

    @Override
    public List<Loan> loadActiveLoansOf(MemberId memberId) {
        journal.add("loadActiveLoansOf");
        return loans.values().stream()
                .filter(loan -> loan.getMemberId().equals(memberId) && loan.isActive())
                .map(InMemoryLoanRepository::copyOf)
                .sorted(Loan.DISPLAY_ORDER)
                .toList();
    }

    @Override
    public Optional<Loan> loadForUpdate(LoanId loanId, MemberId memberId) {
        journal.add("loadForUpdate");
        return Optional.ofNullable(loans.get(loanId))
                .filter(loan -> loan.getMemberId().equals(memberId))
                .map(InMemoryLoanRepository::copyOf);
    }

    @Override
    public void saveNew(Loan loan) {
        journal.add("saveNew");
        saveNewCalls++;
        if (failureOnSaveNew != null) {
            throw failureOnSaveNew;
        }
        loans.put(loan.getId(), copyOf(loan));
    }

    /** Comme l'UPDATE conditionnel : refuse si l'emprunt stocké est déjà rendu. */
    @Override
    public void saveReturned(Loan loan) {
        journal.add("saveReturned");
        saveReturnedCalls++;
        Loan stored = loans.get(loan.getId());
        if (stored == null || !stored.isActive()) {
            throw new LoanAlreadyReturnedException(loan.getId());
        }
        loans.put(loan.getId(), copyOf(loan));
    }

    /** Copie défensive : comme une relecture en base, le use case ne modifie jamais l'état stocké directement. */
    private static Loan copyOf(Loan loan) {
        return Loan.restore(loan.getId(), loan.getMemberId(), loan.getBookId(), loan.getBorrowedOn(),
                loan.getDueOn(), loan.getReturnedOn());
    }
}
