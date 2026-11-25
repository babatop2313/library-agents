package com.example.library.loan.application.port.out;

import java.util.List;

import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.MemberId;

public interface LoadActiveLoansPort {

    /** Emprunts non rendus du membre, par échéance croissante puis identifiant. */
    List<Loan> loadActiveLoansOf(MemberId memberId);
}
