package com.example.library.loan.application.port.in;

import com.example.library.loan.domain.model.Loan;

public interface BorrowBookUseCase {

    /**
     * Emprunte un livre pour le membre (RM-01 à RM-04). Ordre des refus : livre inconnu, retard, limite, livre déjà
     * emprunté, aucun exemplaire disponible. En cas de refus, rien n'est modifié.
     *
     * @return l'emprunt créé
     * @throws com.example.library.loan.domain.exception.BookNotFoundException livre inconnu
     * @throws com.example.library.loan.domain.exception.MemberHasOverdueLoanException membre en retard
     * @throws com.example.library.loan.domain.exception.LoanLimitReachedException limite de 3 atteinte
     * @throws com.example.library.loan.domain.exception.BookAlreadyBorrowedException livre déjà emprunté par le membre
     * @throws com.example.library.loan.domain.exception.BookNotAvailableException plus d'exemplaire disponible
     */
    Loan borrow(BorrowBookCommand command);
}
