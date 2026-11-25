package com.example.library.loan.application.port.out;

import java.util.Map;
import java.util.Set;

import com.example.library.loan.domain.model.BookId;

public interface LoadBookSummariesPort {

    /** Les fiches des livres existants parmi {@code bookIds}, en un seul appel ; les livres disparus sont absents. */
    Map<BookId, BookSummary> loadSummaries(Set<BookId> bookIds);
}
