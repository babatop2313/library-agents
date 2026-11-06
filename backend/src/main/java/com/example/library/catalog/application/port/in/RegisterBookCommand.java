package com.example.library.catalog.application.port.in;

import java.util.Objects;

import com.example.library.catalog.domain.model.Isbn;

public record RegisterBookCommand(Isbn isbn, String title, String author, int copies) {

    public RegisterBookCommand {
        Objects.requireNonNull(isbn, "isbn");
    }
}
