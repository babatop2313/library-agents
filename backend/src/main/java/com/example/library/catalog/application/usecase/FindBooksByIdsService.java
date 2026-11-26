package com.example.library.catalog.application.usecase;

import java.util.List;

import com.example.library.catalog.application.port.in.FindBooksByIdsQuery;
import com.example.library.catalog.application.port.in.FindBooksByIdsUseCase;
import com.example.library.catalog.application.port.out.LoadBooksByIdsPort;
import com.example.library.catalog.domain.model.Book;
import com.example.library.shared.UseCase;

@UseCase(readOnly = true)
public class FindBooksByIdsService implements FindBooksByIdsUseCase {

    private final LoadBooksByIdsPort loadBooksByIdsPort;

    public FindBooksByIdsService(LoadBooksByIdsPort loadBooksByIdsPort) {
        this.loadBooksByIdsPort = loadBooksByIdsPort;
    }

    @Override
    public List<Book> findByIds(FindBooksByIdsQuery query) {
        if (query.ids().isEmpty()) {
            return List.of();
        }
        return loadBooksByIdsPort.loadByIds(query.ids());
    }
}
