package com.example.library.catalog.application.usecase;

import java.util.List;

import com.example.library.catalog.application.port.in.ListAvailableBooksQuery;
import com.example.library.catalog.application.port.in.ListAvailableBooksUseCase;
import com.example.library.catalog.application.port.out.LoadAvailableBooksPort;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.service.AvailableBooksSelection;
import com.example.library.shared.UseCase;

@UseCase(readOnly = true)
public class ListAvailableBooksService implements ListAvailableBooksUseCase {

    private final LoadAvailableBooksPort loadAvailableBooksPort;
    private final AvailableBooksSelection selection = new AvailableBooksSelection();

    public ListAvailableBooksService(LoadAvailableBooksPort loadAvailableBooksPort) {
        this.loadAvailableBooksPort = loadAvailableBooksPort;
    }

    @Override
    public List<Book> listAvailable(ListAvailableBooksQuery query) {
        List<Book> candidates = loadAvailableBooksPort.loadAvailableBooks(query.limit().value());
        return selection.select(candidates, query.limit());
    }
}
