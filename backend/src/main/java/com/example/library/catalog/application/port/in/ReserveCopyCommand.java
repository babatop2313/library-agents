package com.example.library.catalog.application.port.in;

import java.util.Objects;

import com.example.library.catalog.domain.model.BookId;

public record ReserveCopyCommand(BookId bookId) {

    public ReserveCopyCommand {
        Objects.requireNonNull(bookId, "bookId");
    }
}
