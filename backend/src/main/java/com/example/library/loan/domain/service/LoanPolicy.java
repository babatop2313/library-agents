package com.example.library.loan.domain.service;

import java.time.LocalDate;
import java.util.List;

import com.example.library.loan.domain.exception.BookAlreadyBorrowedException;
import com.example.library.loan.domain.exception.LoanLimitReachedException;
import com.example.library.loan.domain.exception.MemberHasOverdueLoanException;
import com.example.library.loan.domain.model.BookId;
import com.example.library.loan.domain.model.Loan;

/** Règles portant sur l'ensemble des emprunts actifs d'un membre : service de domaine, sans état. */
public final class LoanPolicy {

    /** RM-01 : un membre a au maximum 3 emprunts actifs. */
    public static final int MAX_ACTIVE_LOANS = 3;

    /**
     * Vérifie, dans cet ordre : retard (RM-02), limite (RM-01), livre déjà emprunté. Le premier refus rencontré
     * détermine la réponse. {@code activeLoans} ne contient que les emprunts non rendus.
     */
    public void checkCanBorrow(List<Loan> activeLoans, BookId bookId, LocalDate today) {
        if (activeLoans.stream().anyMatch(loan -> loan.isOverdue(today))) {
            throw new MemberHasOverdueLoanException();
        }
        if (activeLoans.size() >= MAX_ACTIVE_LOANS) {
            throw new LoanLimitReachedException(MAX_ACTIVE_LOANS);
        }
        if (activeLoans.stream().anyMatch(loan -> loan.concerns(bookId))) {
            throw new BookAlreadyBorrowedException();
        }
    }
}
