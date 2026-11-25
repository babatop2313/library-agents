package com.example.library.loan.application.fake;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.library.loan.application.port.out.BookSummary;
import com.example.library.loan.application.port.out.CopyRelease;
import com.example.library.loan.application.port.out.CopyReservation;
import com.example.library.loan.application.port.out.LoadBookSummariesPort;
import com.example.library.loan.application.port.out.ReleaseCopyPort;
import com.example.library.loan.application.port.out.ReserveCopyPort;
import com.example.library.loan.domain.model.BookId;

/** Fake du catalogue vu par les emprunts : stock en mémoire, journal d'appels, appels groupés comptés. */
public class FakeCatalog implements ReserveCopyPort, ReleaseCopyPort, LoadBookSummariesPort {

    private final Map<BookId, BookSummary> summaries = new LinkedHashMap<>();
    private final Map<BookId, Integer> available = new HashMap<>();
    private final List<String> journal;
    private final List<Set<BookId>> summaryRequests = new ArrayList<>();
    private CopyReservation forcedReservation;
    private int reserveCalls;
    private int releaseCalls;

    public FakeCatalog() {
        this(new ArrayList<>());
    }

    public FakeCatalog(List<String> journal) {
        this.journal = journal;
    }

    public BookId givenBook(String title, int availableCopies) {
        BookId id = new BookId(java.util.UUID.randomUUID());
        givenBook(id, title, availableCopies);
        return id;
    }

    public void givenBook(BookId id, String title, int availableCopies) {
        summaries.put(id, new BookSummary(id, "978200000" + String.format("%04d", summaries.size()), title,
                "Auteur de " + title));
        available.put(id, availableCopies);
    }

    /** Le livre disparaît du catalogue (suppression entre deux appels). */
    public void removeBook(BookId id) {
        summaries.remove(id);
        available.remove(id);
    }

    /** Impose l'issue de la prochaine réservation, quel que soit le stock (course perdue, livre disparu). */
    public void forceReservation(CopyReservation outcome) {
        this.forcedReservation = outcome;
    }

    public int available(BookId id) {
        return available.get(id);
    }

    public int reserveCalls() {
        return reserveCalls;
    }

    public int releaseCalls() {
        return releaseCalls;
    }

    /** Un élément par appel à {@link #loadSummaries(Set)} : les identifiants demandés. */
    public List<Set<BookId>> summaryRequests() {
        return summaryRequests;
    }

    @Override
    public CopyReservation reserve(BookId bookId) {
        journal.add("reserve");
        reserveCalls++;
        if (forcedReservation != null) {
            return forcedReservation;
        }
        if (!summaries.containsKey(bookId)) {
            return CopyReservation.BOOK_NOT_FOUND;
        }
        if (available.get(bookId) < 1) {
            return CopyReservation.NO_COPY_AVAILABLE;
        }
        available.merge(bookId, -1, Integer::sum);
        return CopyReservation.RESERVED;
    }

    @Override
    public CopyRelease release(BookId bookId) {
        journal.add("release");
        releaseCalls++;
        if (!summaries.containsKey(bookId)) {
            return CopyRelease.BOOK_NOT_FOUND;
        }
        available.merge(bookId, 1, Integer::sum);
        return CopyRelease.RELEASED;
    }

    @Override
    public Map<BookId, BookSummary> loadSummaries(Set<BookId> bookIds) {
        journal.add("loadSummaries");
        summaryRequests.add(Set.copyOf(bookIds));
        Map<BookId, BookSummary> found = new HashMap<>();
        for (BookId id : bookIds) {
            if (summaries.containsKey(id)) {
                found.put(id, summaries.get(id));
            }
        }
        return found;
    }
}
