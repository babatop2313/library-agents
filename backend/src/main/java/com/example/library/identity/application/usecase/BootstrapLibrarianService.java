package com.example.library.identity.application.usecase;

import java.time.Clock;
import java.time.Instant;

import com.example.library.identity.application.port.in.BootstrapLibrarianCommand;
import com.example.library.identity.application.port.in.BootstrapLibrarianUseCase;
import com.example.library.identity.application.port.in.BootstrapResult;
import com.example.library.identity.application.port.out.CheckLibrarianExistsPort;
import com.example.library.identity.application.port.out.LoadUserByEmailPort;
import com.example.library.identity.application.port.out.PasswordHasher;
import com.example.library.identity.application.port.out.SaveUserPort;
import com.example.library.identity.domain.model.BootstrapOutcome;
import com.example.library.identity.domain.model.PasswordHash;
import com.example.library.identity.domain.model.User;
import com.example.library.identity.domain.service.LibrarianBootstrapPolicy;
import com.example.library.shared.UseCase;

/** RM-AUTH-08. Renvoie l'issue sans rien journaliser : c'est l'adapter de démarrage qui s'en charge. */
@UseCase
public class BootstrapLibrarianService implements BootstrapLibrarianUseCase {

    private final CheckLibrarianExistsPort checkLibrarianExistsPort;
    private final LoadUserByEmailPort loadUserByEmailPort;
    private final SaveUserPort saveUserPort;
    private final PasswordHasher passwordHasher;
    private final Clock clock;
    private final LibrarianBootstrapPolicy policy = new LibrarianBootstrapPolicy();

    public BootstrapLibrarianService(CheckLibrarianExistsPort checkLibrarianExistsPort,
                                     LoadUserByEmailPort loadUserByEmailPort, SaveUserPort saveUserPort,
                                     PasswordHasher passwordHasher, Clock clock) {
        this.checkLibrarianExistsPort = checkLibrarianExistsPort;
        this.loadUserByEmailPort = loadUserByEmailPort;
        this.saveUserPort = saveUserPort;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    @Override
    public BootstrapResult bootstrap(BootstrapLibrarianCommand command) {
        BootstrapOutcome outcome = policy.decide(checkLibrarianExistsPort.librarianExists(),
                command.email(), command.password(), loadUserByEmailPort::loadByEmail);
        if (outcome != BootstrapOutcome.CREATED) {
            return BootstrapResult.of(outcome);
        }
        // le hachage (coûteux) n'a lieu que si la création est décidée
        PasswordHash hash = passwordHasher.hash(command.password());
        User librarian = policy.createLibrarian(command.email(), hash, Instant.now(clock));
        saveUserPort.save(librarian);
        return BootstrapResult.created(librarian.id());
    }
}
