package com.example.library.catalog.application.port.in;

import java.util.Objects;

import com.example.library.catalog.domain.model.BookId;

public record ReleaseCopyCommand(BookId bookId) {

    public ReleaseCopyCommand {
        Objects.requireNonNull(bookId, "bookId");
    }
}
