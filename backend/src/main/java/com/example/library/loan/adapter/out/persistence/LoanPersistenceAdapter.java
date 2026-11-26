package com.example.library.loan.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import com.example.library.loan.application.port.out.LoadActiveLoansPort;
import com.example.library.loan.application.port.out.LoadMemberLoanForUpdatePort;
import com.example.library.loan.application.port.out.LockMemberLoansPort;
import com.example.library.loan.application.port.out.SaveLoanPort;
import com.example.library.loan.domain.exception.BookAlreadyBorrowedException;
import com.example.library.loan.domain.exception.LoanAlreadyReturnedException;
import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.LoanId;
import com.example.library.loan.domain.model.MemberId;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class LoanPersistenceAdapter
        implements LockMemberLoansPort, LoadActiveLoansPort, LoadMemberLoanForUpdatePort, SaveLoanPort {

    static final String ACTIVE_LOAN_UNIQUE_INDEX = "uk_loan_member_book_active";

    /** Délai maximal d'attente d'un verrou (verrou du membre, ligne du livre) pour la transaction en cours. */
    static final String LOCK_TIMEOUT = "5s";

    /** Espace de noms du verrou consultatif (0x4C4F414E = « LOAN »), pour ne pas croiser d'autres usages. */
    static final int ADVISORY_LOCK_NAMESPACE = 0x4C4F414E;

    private final LoanJpaRepository repository;
    private final LoanPersistenceMapper mapper;

    LoanPersistenceAdapter(LoanJpaRepository repository, LoanPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * Verrou consultatif par membre, pris en premier dans la transaction d'emprunt. Une collision de hachage entre
     * deux membres sérialise à tort deux requêtes, sans risque pour la correction.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)   // un appel hors transaction lève IllegalTransactionStateException
    public void lockLoansOf(MemberId memberId) {
        repository.setLocalLockTimeout(LOCK_TIMEOUT);
        repository.advisoryLockMember(ADVISORY_LOCK_NAMESPACE, memberId.value().toString());
    }

    @Override
    public List<Loan> loadActiveLoansOf(MemberId memberId) {
        return repository.findByMemberIdAndReturnedOnIsNullOrderByDueOnAscIdAsc(memberId.value()).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public Optional<Loan> loadForUpdate(LoanId loanId, MemberId memberId) {
        // délai fixé pour la transaction (set_config)
        repository.setLocalLockTimeout(LOCK_TIMEOUT);
        return repository.findByIdAndMemberId(loanId.value(), memberId.value()).map(mapper::toDomain);
    }

    @Override
    public void saveNew(Loan loan) {
        try {
            // flush immédiat : la violation de l'index unique doit être détectée ici
            repository.saveAndFlush(mapper.toJpa(loan));
        } catch (DataIntegrityViolationException e) {
            if (isActiveLoanUniqueViolation(e)) {
                throw new BookAlreadyBorrowedException();
            }
            throw e;
        }
    }

    @Override
    public void saveReturned(Loan loan) {
        int rows = repository.markReturned(loan.getId().value(), loan.getMemberId().value(), loan.getReturnedOn());
        if (rows == 0) {
            throw new LoanAlreadyReturnedException(loan.getId());
        }
    }

    private static boolean isActiveLoanUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof ConstraintViolationException cve
                    && ACTIVE_LOAN_UNIQUE_INDEX.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
