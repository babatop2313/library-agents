package com.example.library.loan.application.port.out;

import java.util.Optional;

import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.LoanId;
import com.example.library.loan.domain.model.MemberId;

public interface LoadMemberLoanForUpdatePort {

    /**
     * Emprunt du membre, actif ou rendu, verrouillé jusqu'à la fin de la transaction (retours simultanés sérialisés).
     * Vide si l'emprunt est inconnu ou appartient à un autre membre.
     */
    Optional<Loan> loadForUpdate(LoanId loanId, MemberId memberId);
}
