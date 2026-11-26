package com.example.library.loan.adapter.out.catalog;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.library.catalog.api.CatalogApi;
import com.example.library.catalog.api.ReleaseResult;
import com.example.library.catalog.api.ReservationResult;
import com.example.library.loan.application.port.out.BookSummary;
import com.example.library.loan.application.port.out.CopyRelease;
import com.example.library.loan.application.port.out.CopyReservation;
import com.example.library.loan.application.port.out.LoadBookSummariesPort;
import com.example.library.loan.application.port.out.ReleaseCopyPort;
import com.example.library.loan.application.port.out.ReserveCopyPort;
import com.example.library.loan.domain.model.BookId;
import org.springframework.stereotype.Component;

/**
 * Adapter sortant vers le module catalogue : seul endroit des emprunts qui connaît {@link CatalogApi}.
 * Traduit les types et les raisons de {@code catalog.api} vers ceux du module emprunts.
 */
@Component
class CatalogBooksAdapter implements ReserveCopyPort, ReleaseCopyPort, LoadBookSummariesPort {

    private final CatalogApi catalogApi;

    CatalogBooksAdapter(CatalogApi catalogApi) {
        this.catalogApi = catalogApi;
    }

    @Override
    public CopyReservation reserve(BookId bookId) {
        ReservationResult result = catalogApi.reserveCopy(bookId.value());
        if (result.reserved()) {
            return CopyReservation.RESERVED;
        }
        return switch (String.valueOf(result.reason())) {
            case CatalogApi.BOOK_NOT_FOUND -> CopyReservation.BOOK_NOT_FOUND;
            case CatalogApi.NO_COPY_AVAILABLE -> CopyReservation.NO_COPY_AVAILABLE;
            default -> throw new IllegalStateException("Raison de refus de réservation inconnue : " + result.reason());
        };
    }

    @Override
    public CopyRelease release(BookId bookId) {
        ReleaseResult result = catalogApi.releaseCopy(bookId.value());
        if (result.released()) {
            return CopyRelease.RELEASED;
        }
        if (CatalogApi.BOOK_NOT_FOUND.equals(result.reason())) {
            return CopyRelease.BOOK_NOT_FOUND;
        }
        throw new IllegalStateException("Raison de refus de restitution inconnue : " + result.reason());
    }

    /** Un seul appel au catalogue pour tout le lot. */
    @Override
    public Map<BookId, BookSummary> loadSummaries(Set<BookId> bookIds) {
        Set<UUID> ids = bookIds.stream().map(BookId::value).collect(Collectors.toUnmodifiableSet());
        return catalogApi.findBooksByIds(ids).stream()
                .map(summary -> new BookSummary(new BookId(summary.id()), summary.isbn(),
                        summary.title(), summary.author()))
                .collect(Collectors.toUnmodifiableMap(BookSummary::id, summary -> summary));
    }
}
