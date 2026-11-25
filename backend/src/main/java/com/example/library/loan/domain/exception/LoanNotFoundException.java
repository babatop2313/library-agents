package com.example.library.loan.domain.exception;

/** Aucun emprunt du membre ne correspond à l'identifiant (inconnu ou appartenant à un autre membre). */
public class LoanNotFoundException extends DomainException {

    public LoanNotFoundException() {
        super("LOAN_NOT_FOUND", "Aucun emprunt ne correspond à cet identifiant");
    }
}
