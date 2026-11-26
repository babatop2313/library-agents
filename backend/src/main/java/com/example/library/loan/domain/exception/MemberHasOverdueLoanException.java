package com.example.library.loan.domain.exception;

/** Le membre a un emprunt actif en retard : il ne peut plus emprunter (RM-02). */
public class MemberHasOverdueLoanException extends DomainException {

    public MemberHasOverdueLoanException() {
        super("MEMBER_HAS_OVERDUE_LOAN",
                "Vous avez un emprunt en retard : rendez-le avant d'emprunter un autre livre");
    }
}
