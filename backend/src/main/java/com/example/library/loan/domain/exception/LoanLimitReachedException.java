package com.example.library.loan.domain.exception;

/** Le membre a déjà atteint le nombre maximal d'emprunts actifs (RM-01). */
public class LoanLimitReachedException extends DomainException {

    private final int max;

    public LoanLimitReachedException(int max) {
        super("LOAN_LIMIT_REACHED", "Vous avez déjà " + max + " emprunts en cours");
        this.max = max;
    }

    public int getMax() {
        return max;
    }
}
