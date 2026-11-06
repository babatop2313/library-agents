package com.example.library.catalog.application.usecase;

import com.example.library.catalog.application.port.in.RegisterBookCommand;
import com.example.library.catalog.application.port.in.RegisterBookUseCase;
import com.example.library.catalog.application.port.out.CheckIsbnRegisteredPort;
import com.example.library.catalog.application.port.out.SaveBookPort;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.service.BookRegistrationPolicy;
import com.example.library.shared.UseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@UseCase
public class RegisterBookService implements RegisterBookUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterBookService.class);

    private final CheckIsbnRegisteredPort checkIsbnRegisteredPort;
    private final SaveBookPort saveBookPort;
    private final BookRegistrationPolicy registrationPolicy = new BookRegistrationPolicy();

    public RegisterBookService(CheckIsbnRegisteredPort checkIsbnRegisteredPort, SaveBookPort saveBookPort) {
        this.checkIsbnRegisteredPort = checkIsbnRegisteredPort;
        this.saveBookPort = saveBookPort;
    }

    @Override
    public Book register(RegisterBookCommand command) {
        Book book = Book.register(command.isbn(), command.title(), command.author(), command.copies());
        registrationPolicy.ensureIsbnIsFree(command.isbn(), checkIsbnRegisteredPort.isRegistered(command.isbn()));
        saveBookPort.save(book);
        log.info("Livre enregistré : id={}, isbn={}", book.getId().value(), book.getIsbn().value());
        return book;
    }
}
