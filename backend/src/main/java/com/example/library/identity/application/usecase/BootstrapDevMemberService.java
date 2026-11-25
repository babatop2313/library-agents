package com.example.library.identity.application.usecase;

import java.time.Clock;
import java.time.Instant;

import com.example.library.identity.application.port.in.BootstrapDevMemberCommand;
import com.example.library.identity.application.port.in.BootstrapDevMemberUseCase;
import com.example.library.identity.application.port.in.DevMemberBootstrapResult;
import com.example.library.identity.application.port.out.LoadUserByEmailPort;
import com.example.library.identity.application.port.out.PasswordHasher;
import com.example.library.identity.application.port.out.SaveUserPort;
import com.example.library.identity.domain.model.DevMemberBootstrapOutcome;
import com.example.library.identity.domain.model.PasswordHash;
import com.example.library.identity.domain.model.User;
import com.example.library.identity.domain.service.DevMemberBootstrapPolicy;
import com.example.library.shared.UseCase;

/** RM-LOAN-30. Renvoie l'issue sans rien journaliser : c'est l'adapter de démarrage qui s'en charge. */
@UseCase
public class BootstrapDevMemberService implements BootstrapDevMemberUseCase {

    private final LoadUserByEmailPort loadUserByEmailPort;
    private final SaveUserPort saveUserPort;
    private final PasswordHasher passwordHasher;
    private final Clock clock;
    private final DevMemberBootstrapPolicy policy = new DevMemberBootstrapPolicy();

    public BootstrapDevMemberService(LoadUserByEmailPort loadUserByEmailPort, SaveUserPort saveUserPort,
                                     PasswordHasher passwordHasher, Clock clock) {
        this.loadUserByEmailPort = loadUserByEmailPort;
        this.saveUserPort = saveUserPort;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    @Override
    public DevMemberBootstrapResult bootstrap(BootstrapDevMemberCommand command) {
        DevMemberBootstrapOutcome outcome =
                policy.decide(command.email(), command.password(), loadUserByEmailPort::loadByEmail);
        if (outcome != DevMemberBootstrapOutcome.CREATED) {
            return DevMemberBootstrapResult.of(outcome);
        }
        // le hachage (coûteux) n'a lieu que si la création est décidée
        PasswordHash hash = passwordHasher.hash(command.password());
        User member = policy.createMember(command.email(), hash, Instant.now(clock));
        saveUserPort.save(member);
        return DevMemberBootstrapResult.created(member.id());
    }
}
