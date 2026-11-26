package com.example.library.loan.domain.exception;

import java.util.Objects;

import com.example.library.loan.domain.model.LoanId;

/** L'emprunt a déjà été rendu : on ne rend pas deux fois le même emprunt (RM-05). */
public class LoanAlreadyReturnedException extends DomainException {

    private final LoanId loanId;

    public LoanAlreadyReturnedException(LoanId loanId) {
        super("LOAN_ALREADY_RETURNED", "Cet emprunt a déjà été rendu");
        this.loanId = Objects.requireNonNull(loanId, "loanId");
    }

    public LoanId getLoanId() {
        return loanId;
    }
}
