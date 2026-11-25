package com.example.library.catalog.application.usecase;

import java.util.Optional;

import com.example.library.catalog.application.port.in.CopyReservationOutcome;
import com.example.library.catalog.application.port.in.ReserveCopyCommand;
import com.example.library.catalog.application.port.in.ReserveCopyUseCase;
import com.example.library.catalog.application.port.out.ChangeAvailableCopiesPort;
import com.example.library.catalog.application.port.out.LoadBookByIdPort;
import com.example.library.catalog.domain.exception.NoCopyAvailableException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.shared.UseCase;

/**
 * RM-04. Le domaine décide ({@link Book#takeCopy()}), puis la mise à jour conditionnelle atomique arbitre la course
 * entre deux emprunts simultanés. Les cas attendus sont renvoyés comme issues : aucune exception ne traverse le proxy
 * transactionnel, sinon la transaction du module d'emprunts serait marquée « rollback-only ».
 */
@UseCase
public class ReserveCopyService implements ReserveCopyUseCase {

    private final LoadBookByIdPort loadBookByIdPort;
    private final ChangeAvailableCopiesPort changeAvailableCopiesPort;

    public ReserveCopyService(LoadBookByIdPort loadBookByIdPort, ChangeAvailableCopiesPort changeAvailableCopiesPort) {
        this.loadBookByIdPort = loadBookByIdPort;
        this.changeAvailableCopiesPort = changeAvailableCopiesPort;
    }

    @Override
    public CopyReservationOutcome reserve(ReserveCopyCommand command) {
        Optional<Book> book = loadBookByIdPort.loadById(command.bookId());
        if (book.isEmpty()) {
            return CopyReservationOutcome.BOOK_NOT_FOUND;
        }
        try {
            // le domaine décide ; l'UPDATE conditionnel applique
            book.get().takeCopy();
        } catch (NoCopyAvailableException e) {
            return CopyReservationOutcome.NO_COPY_AVAILABLE;
        }
        // course perdue contre un autre emprunt du dernier exemplaire : la condition SQL n'est plus vraie
        return changeAvailableCopiesPort.decrementAvailableCopies(command.bookId())
                ? CopyReservationOutcome.RESERVED
                : CopyReservationOutcome.NO_COPY_AVAILABLE;
    }
}
