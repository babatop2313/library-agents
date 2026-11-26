package com.example.library.loan.application.port.out;

import com.example.library.loan.domain.model.Loan;

public interface SaveLoanPort {

    /**
     * Enregistre un nouvel emprunt.
     *
     * @throws com.example.library.loan.domain.exception.BookAlreadyBorrowedException si l'index unique
     *         (un seul emprunt actif par membre et par livre) est violé : filet de sécurité de la politique
     */
    void saveNew(Loan loan);

    /**
     * Enregistre le retour d'un emprunt (mise à jour conditionnelle : seulement s'il n'est pas déjà rendu).
     *
     * @throws com.example.library.loan.domain.exception.LoanAlreadyReturnedException si aucune ligne n'est modifiée
     */
    void saveReturned(Loan loan);
}
