package com.example.library.loan.application.port.in;

import java.time.LocalDate;

import com.example.library.loan.domain.model.BookId;
import com.example.library.loan.domain.model.LoanId;

/**
 * Emprunt en cours, enrichi des informations du livre. Champs aplatis : un adapter entrant ne voit aucun type de
 * {@code port.out}. {@code isbn}, {@code title} et {@code author} sont nuls si le livre n'existe plus au catalogue.
 */
public record CurrentLoanView(LoanId id, BookId bookId, String isbn, String title, String author,
                              LocalDate borrowedOn, LocalDate dueOn, boolean overdue) {
}
