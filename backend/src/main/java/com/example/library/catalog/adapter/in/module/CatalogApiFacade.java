package com.example.library.catalog.adapter.in.module;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.library.catalog.api.BookSummary;
import com.example.library.catalog.api.CatalogApi;
import com.example.library.catalog.api.ReleaseResult;
import com.example.library.catalog.api.ReservationResult;
import com.example.library.catalog.application.port.in.CopyReleaseOutcome;
import com.example.library.catalog.application.port.in.CopyReservationOutcome;
import com.example.library.catalog.application.port.in.FindBooksByIdsQuery;
import com.example.library.catalog.application.port.in.FindBooksByIdsUseCase;
import com.example.library.catalog.application.port.in.ReleaseCopyCommand;
import com.example.library.catalog.application.port.in.ReleaseCopyUseCase;
import com.example.library.catalog.application.port.in.ReserveCopyCommand;
import com.example.library.catalog.application.port.in.ReserveCopyUseCase;
import com.example.library.catalog.domain.model.BookId;
import org.springframework.stereotype.Component;

/**
 * Implémentation du contrat public {@link CatalogApi} : comme un contrôleur, elle n'appelle que les ports d'entrée
 * du catalogue et traduit les types {@code api} (UUID, chaînes) vers ceux du domaine.
 */
@Component
class CatalogApiFacade implements CatalogApi {

    private final ReserveCopyUseCase reserveCopyUseCase;
    private final ReleaseCopyUseCase releaseCopyUseCase;
    private final FindBooksByIdsUseCase findBooksByIdsUseCase;

    CatalogApiFacade(ReserveCopyUseCase reserveCopyUseCase, ReleaseCopyUseCase releaseCopyUseCase,
                     FindBooksByIdsUseCase findBooksByIdsUseCase) {
        this.reserveCopyUseCase = reserveCopyUseCase;
        this.releaseCopyUseCase = releaseCopyUseCase;
        this.findBooksByIdsUseCase = findBooksByIdsUseCase;
    }

    @Override
    public ReservationResult reserveCopy(UUID bookId) {
        CopyReservationOutcome outcome = reserveCopyUseCase.reserve(new ReserveCopyCommand(new BookId(bookId)));
        return switch (outcome) {
            case RESERVED -> new ReservationResult(true, null);
            case BOOK_NOT_FOUND -> new ReservationResult(false, BOOK_NOT_FOUND);
            case NO_COPY_AVAILABLE -> new ReservationResult(false, NO_COPY_AVAILABLE);
        };
    }

    @Override
    public ReleaseResult releaseCopy(UUID bookId) {
        CopyReleaseOutcome outcome = releaseCopyUseCase.release(new ReleaseCopyCommand(new BookId(bookId)));
        return switch (outcome) {
            case RELEASED -> new ReleaseResult(true, null);
            case BOOK_NOT_FOUND -> new ReleaseResult(false, BOOK_NOT_FOUND);
        };
    }

    @Override
    public List<BookSummary> findBooksByIds(Collection<UUID> bookIds) {
        Set<BookId> ids = bookIds.stream().map(BookId::new).collect(Collectors.toUnmodifiableSet());
        return findBooksByIdsUseCase.findByIds(new FindBooksByIdsQuery(ids)).stream()
                .map(book -> new BookSummary(book.getId().value(), book.getIsbn().value(),
                        book.getTitle(), book.getAuthor()))
                .toList();
    }
}
