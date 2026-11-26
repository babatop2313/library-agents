package com.example.library.loan.application.port.out;

import com.example.library.loan.domain.model.MemberId;

public interface LockMemberLoansPort {

    /**
     * Sérialise les emprunts d'un même membre jusqu'à la fin de la transaction en cours : à appeler en premier,
     * avant de lire les emprunts actifs, pour que la limite de 3 (RM-01) tienne sous concurrence.
     * Doit être appelé dans une transaction.
     */
    void lockLoansOf(MemberId memberId);
}
