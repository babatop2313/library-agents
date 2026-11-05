package com.example.library.catalog.adapter.in.web;

import com.example.library.catalog.domain.model.Book;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

record RegisterBookRequest(
        @NotNull @ValidIsbnFormat String isbn,
        @NotBlank @MaxTrimmedLength String title,
        @NotBlank @MaxTrimmedLength String author,
        @NotNull @Min(Book.MIN_COPIES) @Max(Book.MAX_COPIES) Integer copies) {
}
