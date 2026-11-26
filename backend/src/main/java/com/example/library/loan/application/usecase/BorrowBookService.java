package com.example.library.loan.application.usecase;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;

import com.example.library.loan.application.port.in.BorrowBookCommand;
import com.example.library.loan.application.port.in.BorrowBookUseCase;
import com.example.library.loan.application.port.out.CopyReservation;
import com.example.library.loan.application.port.out.LoadActiveLoansPort;
import com.example.library.loan.application.port.out.LoadBookSummariesPort;
import com.example.library.loan.application.port.out.LockMemberLoansPort;
import com.example.library.loan.application.port.out.ReserveCopyPort;
import com.example.library.loan.application.port.out.SaveLoanPort;
import com.example.library.loan.domain.exception.BookNotAvailableException;
import com.example.library.loan.domain.exception.BookNotFoundException;
import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.service.LoanPolicy;
import com.example.library.shared.UseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emprunter un livre. Le use case orchestre ; les règles (retard, limite, livre déjà emprunté, échéance) sont dans
 * le domaine. La réservation de l'exemplaire et l'insertion de l'emprunt partagent la même transaction : tout échec
 * annule l'autre.
 *
 * <p>Ordre : 1. verrou du membre (avant toute lecture, pour sérialiser les emprunts simultanés du même membre) ;
 * 2. livre connu ; 3. politique ; 4. réservation de l'exemplaire ; 5. création de l'emprunt.
 */
@UseCase
public class BorrowBookService implements BorrowBookUseCase {

    private static final Logger log = LoggerFactory.getLogger(BorrowBookService.class);

    private final LockMemberLoansPort lockMemberLoansPort;
    private final LoadBookSummariesPort loadBookSummariesPort;
    private final LoadActiveLoansPort loadActiveLoansPort;
    private final ReserveCopyPort reserveCopyPort;
    private final SaveLoanPort saveLoanPort;
    private final Clock clock;
    private final LoanPolicy loanPolicy = new LoanPolicy();

    public BorrowBookService(LockMemberLoansPort lockMemberLoansPort, LoadBookSummariesPort loadBookSummariesPort,
                             LoadActiveLoansPort loadActiveLoansPort, ReserveCopyPort reserveCopyPort,
                             SaveLoanPort saveLoanPort, Clock clock) {
        this.lockMemberLoansPort = lockMemberLoansPort;
        this.loadBookSummariesPort = loadBookSummariesPort;
        this.loadActiveLoansPort = loadActiveLoansPort;
        this.reserveCopyPort = reserveCopyPort;
        this.saveLoanPort = saveLoanPort;
        this.clock = clock;
    }

    @Override
    public Loan borrow(BorrowBookCommand command) {
        LocalDate today = LocalDate.now(clock);
        lockMemberLoansPort.lockLoansOf(command.memberId());
        if (!loadBookSummariesPort.loadSummaries(Set.of(command.bookId())).containsKey(command.bookId())) {
            throw new BookNotFoundException(command.bookId());
        }
        loanPolicy.checkCanBorrow(loadActiveLoansPort.loadActiveLoansOf(command.memberId()), command.bookId(), today);
        CopyReservation reservation = reserveCopyPort.reserve(command.bookId());
        switch (reservation) {
            case RESERVED -> { }
            case BOOK_NOT_FOUND -> throw new BookNotFoundException(command.bookId());
            case NO_COPY_AVAILABLE -> throw new BookNotAvailableException(command.bookId());
        }
        Loan loan = Loan.open(command.memberId(), command.bookId(), today);
        saveLoanPort.saveNew(loan);
        log.info("Emprunt créé : loanId={}, memberId={}, bookId={}",
                loan.getId().value(), loan.getMemberId().value(), loan.getBookId().value());
        return loan;
    }
}
