package com.example.library.identity.application.usecase;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.example.library.identity.application.port.in.LoginCommand;
import com.example.library.identity.application.port.in.LoginResult;
import com.example.library.identity.application.port.in.LoginUseCase;
import com.example.library.identity.application.port.out.AccessTokenIssuer;
import com.example.library.identity.application.port.out.LoadUserByEmailPort;
import com.example.library.identity.application.port.out.PasswordHasher;
import com.example.library.identity.domain.exception.InvalidCredentialsException;
import com.example.library.identity.domain.model.AccessToken;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.PasswordHash;
import com.example.library.identity.domain.model.User;
import com.example.library.identity.domain.service.LoginPolicy;
import com.example.library.shared.UseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@UseCase(readOnly = true)
public class LoginService implements LoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final LoadUserByEmailPort loadUserByEmailPort;
    private final PasswordHasher passwordHasher;
    private final AccessTokenIssuer accessTokenIssuer;
    private final Clock clock;
    private final LoginPolicy loginPolicy = new LoginPolicy();

    public LoginService(LoadUserByEmailPort loadUserByEmailPort, PasswordHasher passwordHasher,
                        AccessTokenIssuer accessTokenIssuer, Clock clock) {
        this.loadUserByEmailPort = loadUserByEmailPort;
        this.passwordHasher = passwordHasher;
        this.accessTokenIssuer = accessTokenIssuer;
        this.clock = clock;
    }

    @Override
    public LoginResult login(LoginCommand command) {
        Email email = Email.of(command.email());
        try {
            Optional<User> user = loadUserByEmailPort.loadByEmail(email);
            PasswordHash reference = user.map(User::passwordHash).orElseGet(passwordHasher::dummyHash);
            boolean matches = passwordHasher.matches(command.password(), reference);   // TOUJOURS exécuté (RM-AUTH-07)
            User authenticated = loginPolicy.authenticate(user, command.password(), matches);
            AccessToken token = accessTokenIssuer.issue(authenticated, Instant.now(clock));
            log.info("Connexion réussie : userId={}", authenticated.id().value());
            return new LoginResult(authenticated.id(), authenticated.email(), authenticated.roles(), token);
        } catch (InvalidCredentialsException e) {
            // pas d'identifiant connu à ce stade : l'e-mail n'apparaît que masqué (RM-AUTH-11)
            log.warn("Échec de connexion pour {}", email.masked());
            throw e;
        }
    }
}
