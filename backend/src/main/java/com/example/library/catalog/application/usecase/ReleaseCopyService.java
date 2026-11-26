package com.example.library.catalog.application.usecase;

import java.util.Optional;

import com.example.library.catalog.application.port.in.CopyReleaseOutcome;
import com.example.library.catalog.application.port.in.ReleaseCopyCommand;
import com.example.library.catalog.application.port.in.ReleaseCopyUseCase;
import com.example.library.catalog.application.port.out.ChangeAvailableCopiesPort;
import com.example.library.catalog.application.port.out.LoadBookByIdPort;
import com.example.library.catalog.domain.exception.AvailableCopiesOverflowException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.shared.UseCase;

/**
 * Restitution d'un exemplaire. Livre absent : issue {@code BOOK_NOT_FOUND}. Dépassement du total : exception
 * technique (incohérence de données), qui annule la transaction de l'appelant.
 */
@UseCase
public class ReleaseCopyService implements ReleaseCopyUseCase {

    private final LoadBookByIdPort loadBookByIdPort;
    private final ChangeAvailableCopiesPort changeAvailableCopiesPort;

    public ReleaseCopyService(LoadBookByIdPort loadBookByIdPort, ChangeAvailableCopiesPort changeAvailableCopiesPort) {
        this.loadBookByIdPort = loadBookByIdPort;
        this.changeAvailableCopiesPort = changeAvailableCopiesPort;
    }

    @Override
    public CopyReleaseOutcome release(ReleaseCopyCommand command) {
        Optional<Book> book = loadBookByIdPort.loadById(command.bookId());
        if (book.isEmpty()) {
            return CopyReleaseOutcome.BOOK_NOT_FOUND;
        }
        // le domaine décide ; l'UPDATE conditionnel applique
        book.get().returnCopy();
        if (!changeAvailableCopiesPort.incrementAvailableCopies(command.bookId())) {
            // aucune ligne modifiée alors que le domaine acceptait : stock au maximum, ou livre supprimé entre la lecture et l'UPDATE
            throw new AvailableCopiesOverflowException(command.bookId(),
                    "Restitution impossible : stock déjà au maximum, ou livre supprimé entre la lecture et la mise à jour");
        }
        return CopyReleaseOutcome.RELEASED;
    }
}
