package com.example.library.loan.application.port.in;

import java.util.List;

public interface ListCurrentLoansUseCase {

    /** Emprunts actifs du membre (au plus 3), par échéance croissante puis identifiant. Liste vide si aucun. */
    List<CurrentLoanView> listCurrentLoans(ListCurrentLoansQuery query);
}
