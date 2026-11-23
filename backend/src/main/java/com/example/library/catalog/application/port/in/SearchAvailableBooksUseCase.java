package com.example.library.catalog.application.port.in;

import com.example.library.catalog.domain.model.AvailableBooksPage;

public interface SearchAvailableBooksUseCase {

    AvailableBooksPage search(SearchAvailableBooksQuery query);
}
