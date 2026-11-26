package com.example.library.loan.adapter.in.web;

import com.example.library.loan.application.port.in.BorrowBookCommand;
import com.example.library.loan.application.port.in.CurrentLoanView;
import com.example.library.loan.domain.model.BookId;
import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.MemberId;

final class LoanWebMapper {

    private LoanWebMapper() {
    }

    static BorrowBookCommand toCommand(MemberId memberId, BorrowBookRequest request) {
        return new BorrowBookCommand(memberId, new BookId(request.bookId()));
    }

    static LoanCreatedResponse toCreatedResponse(Loan loan) {
        return new LoanCreatedResponse(loan.getId().value(), loan.getBookId().value(),
                loan.getBorrowedOn(), loan.getDueOn());
    }

    static CurrentLoanResponse toResponse(CurrentLoanView view) {
        return new CurrentLoanResponse(view.id().value(), view.bookId().value(), view.isbn(), view.title(),
                view.author(), view.borrowedOn(), view.dueOn(), view.overdue());
    }
}
