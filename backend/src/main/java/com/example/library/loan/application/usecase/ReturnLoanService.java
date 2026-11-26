package com.example.library.loan.application.usecase;

import java.time.Clock;
import java.time.LocalDate;

import com.example.library.loan.application.port.in.ReturnLoanCommand;
import com.example.library.loan.application.port.in.ReturnLoanUseCase;
import com.example.library.loan.application.port.out.CopyRelease;
import com.example.library.loan.application.port.out.LoadMemberLoanForUpdatePort;
import com.example.library.loan.application.port.out.ReleaseCopyPort;
import com.example.library.loan.application.port.out.SaveLoanPort;
import com.example.library.loan.domain.exception.LoanNotFoundException;
import com.example.library.loan.domain.model.Loan;
import com.example.library.shared.UseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rendre un emprunt. Le retour de l'emprunt et la restitution de l'exemplaire partagent la même transaction.
 * Si le livre a disparu du catalogue entre-temps, le retour est conservé (aucun exemplaire à restituer) et un WARN
 * est journalisé.
 */
@UseCase
public class ReturnLoanService implements ReturnLoanUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReturnLoanService.class);

    private final LoadMemberLoanForUpdatePort loadMemberLoanForUpdatePort;
    private final SaveLoanPort saveLoanPort;
    private final ReleaseCopyPort releaseCopyPort;
    private final Clock clock;

    public ReturnLoanService(LoadMemberLoanForUpdatePort loadMemberLoanForUpdatePort, SaveLoanPort saveLoanPort,
                             ReleaseCopyPort releaseCopyPort, Clock clock) {
        this.loadMemberLoanForUpdatePort = loadMemberLoanForUpdatePort;
        this.saveLoanPort = saveLoanPort;
        this.releaseCopyPort = releaseCopyPort;
        this.clock = clock;
    }

    @Override
    public void giveBack(ReturnLoanCommand command) {
        LocalDate today = LocalDate.now(clock);
        Loan loan = loadMemberLoanForUpdatePort.loadForUpdate(command.loanId(), command.memberId())
                .orElseThrow(LoanNotFoundException::new);
        loan.giveBack(today);
        saveLoanPort.saveReturned(loan);
        if (releaseCopyPort.release(loan.getBookId()) == CopyRelease.BOOK_NOT_FOUND) {
            log.warn("Retour enregistré sans exemplaire à restituer : livre absent du catalogue, loanId={}, bookId={}",
                    loan.getId().value(), loan.getBookId().value());
        }
        log.info("Emprunt rendu : loanId={}, memberId={}, bookId={}",
                loan.getId().value(), loan.getMemberId().value(), loan.getBookId().value());
    }
}
