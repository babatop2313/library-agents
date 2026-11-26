package com.example.library.loan.application.port.in;

public interface ReturnLoanUseCase {

    /**
     * Rend un emprunt du membre (RM-05) : l'exemplaire redevient disponible dans la même transaction.
     *
     * @throws com.example.library.loan.domain.exception.LoanNotFoundException emprunt inconnu ou d'un autre membre
     * @throws com.example.library.loan.domain.exception.LoanAlreadyReturnedException emprunt déjà rendu
     */
    void giveBack(ReturnLoanCommand command);
}
