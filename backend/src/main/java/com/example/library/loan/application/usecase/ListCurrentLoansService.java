package com.example.library.loan.application.usecase;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.library.loan.application.port.in.CurrentLoanView;
import com.example.library.loan.application.port.in.ListCurrentLoansQuery;
import com.example.library.loan.application.port.in.ListCurrentLoansUseCase;
import com.example.library.loan.application.port.out.BookSummary;
import com.example.library.loan.application.port.out.LoadActiveLoansPort;
import com.example.library.loan.application.port.out.LoadBookSummariesPort;
import com.example.library.loan.domain.model.BookId;
import com.example.library.loan.domain.model.Loan;
import com.example.library.shared.UseCase;

/** Emprunts en cours d'un membre, enrichis des fiches des livres en un seul appel au catalogue. */
@UseCase(readOnly = true)
public class ListCurrentLoansService implements ListCurrentLoansUseCase {

    private final LoadActiveLoansPort loadActiveLoansPort;
    private final LoadBookSummariesPort loadBookSummariesPort;
    private final Clock clock;

    public ListCurrentLoansService(LoadActiveLoansPort loadActiveLoansPort,
                                   LoadBookSummariesPort loadBookSummariesPort, Clock clock) {
        this.loadActiveLoansPort = loadActiveLoansPort;
        this.loadBookSummariesPort = loadBookSummariesPort;
        this.clock = clock;
    }

    @Override
    public List<CurrentLoanView> listCurrentLoans(ListCurrentLoansQuery query) {
        List<Loan> loans = loadActiveLoansPort.loadActiveLoansOf(query.memberId());
        if (loans.isEmpty()) {
            return List.of();
        }
        LocalDate today = LocalDate.now(clock);
        Set<BookId> bookIds = loans.stream().map(Loan::getBookId).collect(Collectors.toUnmodifiableSet());
        Map<BookId, BookSummary> summaries = loadBookSummariesPort.loadSummaries(bookIds);
        return loans.stream()
                .sorted(Loan.DISPLAY_ORDER)
                .map(loan -> toView(loan, summaries.get(loan.getBookId()), today))
                .toList();
    }

    /** Livre disparu du catalogue : les champs du livre sont nuls. */
    private static CurrentLoanView toView(Loan loan, BookSummary summary, LocalDate today) {
        return new CurrentLoanView(loan.getId(), loan.getBookId(),
                summary == null ? null : summary.isbn(),
                summary == null ? null : summary.title(),
                summary == null ? null : summary.author(),
                loan.getBorrowedOn(), loan.getDueOn(), loan.isOverdue(today));
    }
}
