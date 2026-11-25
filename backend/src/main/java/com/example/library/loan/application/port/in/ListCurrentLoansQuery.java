package com.example.library.loan.application.port.in;

import java.util.Objects;

import com.example.library.loan.domain.model.MemberId;

public record ListCurrentLoansQuery(MemberId memberId) {

    public ListCurrentLoansQuery {
        Objects.requireNonNull(memberId, "memberId");
    }
}
