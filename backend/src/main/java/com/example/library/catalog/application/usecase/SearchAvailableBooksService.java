package com.example.library.catalog.application.usecase;

import com.example.library.catalog.application.port.in.SearchAvailableBooksQuery;
import com.example.library.catalog.application.port.in.SearchAvailableBooksUseCase;
import com.example.library.catalog.application.port.out.CountAvailableBooksPort;
import com.example.library.catalog.application.port.out.LoadAvailableBooksPagePort;
import com.example.library.catalog.domain.model.AvailableBooksPage;
import com.example.library.catalog.domain.model.CatalogPageRequest;
import com.example.library.catalog.domain.model.SearchText;
import com.example.library.shared.UseCase;

@UseCase(readOnly = true)
public class SearchAvailableBooksService implements SearchAvailableBooksUseCase {

    private final LoadAvailableBooksPagePort loadAvailableBooksPagePort;
    private final CountAvailableBooksPort countAvailableBooksPort;

    public SearchAvailableBooksService(LoadAvailableBooksPagePort loadAvailableBooksPagePort,
                                       CountAvailableBooksPort countAvailableBooksPort) {
        this.loadAvailableBooksPagePort = loadAvailableBooksPagePort;
        this.countAvailableBooksPort = countAvailableBooksPort;
    }

    @Override
    public AvailableBooksPage search(SearchAvailableBooksQuery query) {
        SearchText text = query.search().text();
        CatalogPageRequest request = query.search().pageRequest();
        long total = countAvailableBooksPort.countAvailableBooks(text);
        if (total == 0 || request.offset() >= total) {
            return AvailableBooksPage.empty(request, total);
        }
        return new AvailableBooksPage(loadAvailableBooksPagePort.loadAvailableBooksPage(text, request), request, total);
    }
}
