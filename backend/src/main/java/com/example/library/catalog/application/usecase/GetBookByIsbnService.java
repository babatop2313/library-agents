package com.example.library.catalog.application.usecase;

import com.example.library.catalog.application.port.in.GetBookByIsbnQuery;
import com.example.library.catalog.application.port.in.GetBookByIsbnUseCase;
import com.example.library.catalog.application.port.out.LoadBookByIsbnPort;
import com.example.library.catalog.domain.exception.BookNotFoundException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.shared.UseCase;

@UseCase(readOnly = true)
public class GetBookByIsbnService implements GetBookByIsbnUseCase {

    private final LoadBookByIsbnPort loadBookByIsbnPort;

    public GetBookByIsbnService(LoadBookByIsbnPort loadBookByIsbnPort) {
        this.loadBookByIsbnPort = loadBookByIsbnPort;
    }

    @Override
    public Book getByIsbn(GetBookByIsbnQuery query) {
        return loadBookByIsbnPort.loadByIsbn(query.isbn())
                .orElseThrow(() -> new BookNotFoundException(query.isbn()));
    }
}
