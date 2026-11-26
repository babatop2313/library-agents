package com.example.library.loan.application.port.in;

import java.util.Objects;

import com.example.library.loan.domain.model.LoanId;
import com.example.library.loan.domain.model.MemberId;

public record ReturnLoanCommand(MemberId memberId, LoanId loanId) {

    public ReturnLoanCommand {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(loanId, "loanId");
    }
}
