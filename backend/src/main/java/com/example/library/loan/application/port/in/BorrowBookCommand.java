package com.example.library.loan.application.port.in;

import java.util.Objects;

import com.example.library.loan.domain.model.BookId;
import com.example.library.loan.domain.model.MemberId;

public record BorrowBookCommand(MemberId memberId, BookId bookId) {

    public BorrowBookCommand {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(bookId, "bookId");
    }
}
