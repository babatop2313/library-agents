package com.example.library.catalog.application.port.in;

import com.example.library.catalog.domain.model.Book;

public interface RegisterBookUseCase {

    Book register(RegisterBookCommand command);
}
