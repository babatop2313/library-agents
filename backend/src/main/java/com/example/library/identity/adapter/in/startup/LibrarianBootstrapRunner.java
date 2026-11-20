package com.example.library.identity.adapter.in.startup;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.example.library.identity.application.port.in.BootstrapLibrarianCommand;
import com.example.library.identity.application.port.in.BootstrapLibrarianUseCase;
import com.example.library.identity.application.port.in.BootstrapResult;
import com.example.library.identity.config.LibrarianBootstrapProperties;
import com.example.library.identity.domain.exception.InvalidEmailException;
import com.example.library.identity.domain.model.Email;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * RM-AUTH-08 : crée le premier bibliothécaire au démarrage, si besoin. N'appelle que le port d'entrée et ne fait
 * jamais échouer le démarrage. Messages figés : ni le mot de passe, ni sa longueur, ni la commande (toString) n'y
 * figurent ; l'e-mail n'apparaît que masqué, dans le seul WARN qui n'a pas d'identifiant utilisateur.
 */
@Component
class LibrarianBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LibrarianBootstrapRunner.class);

    private final BootstrapLibrarianUseCase bootstrapLibrarianUseCase;
    private final LibrarianBootstrapProperties properties;

    LibrarianBootstrapRunner(BootstrapLibrarianUseCase bootstrapLibrarianUseCase,
                             LibrarianBootstrapProperties properties) {
        this.bootstrapLibrarianUseCase = bootstrapLibrarianUseCase;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            BootstrapResult result = bootstrapLibrarianUseCase.bootstrap(
                    new BootstrapLibrarianCommand(properties.email(), properties.password()));
            report(result);
        } catch (DataIntegrityViolationException e) {
            // création concurrente (autre instance) : le message du SGBD peut contenir l'e-mail, on ne le journalise pas
            log.warn("Amorçage du bibliothécaire ignoré : conflit d'intégrité (création concurrente probable)");
        } catch (RuntimeException e) {
            log.warn("Amorçage du bibliothécaire en échec ({}) : le démarrage continue", e.getClass().getSimpleName());
            if (log.isDebugEnabled()) {
                log.debug("Détail de l'échec d'amorçage : {}", diagnostic(e));
            }
        }
    }

    private void report(BootstrapResult result) {
        switch (result.outcome()) {
            case CREATED -> log.info("Bibliothécaire d'amorçage créé : userId={}", result.createdUserId().value());
            case LIBRARIAN_ALREADY_PRESENT -> log.debug("Bibliothécaire déjà présent : aucun amorçage");
            case MISSING_CONFIGURATION -> log.warn(
                    "BOOTSTRAP_LIBRARIAN_EMAIL ou BOOTSTRAP_LIBRARIAN_PASSWORD absent : aucun bibliothécaire créé");
            case INVALID_EMAIL -> log.warn(
                    "BOOTSTRAP_LIBRARIAN_EMAIL n'est pas une adresse e-mail valide : aucun bibliothécaire créé");
            case WEAK_PASSWORD -> log.warn("BOOTSTRAP_LIBRARIAN_PASSWORD ne respecte pas la politique "
                    + "(12 caractères minimum, 72 octets maximum) : aucun bibliothécaire créé");
            case EMAIL_TAKEN_BY_NON_LIBRARIAN -> log.warn(
                    "L'e-mail d'amorçage {} appartient déjà à un utilisateur sans le rôle LIBRARIAN : "
                            + "aucun bibliothécaire créé, utilisateur inchangé", maskedEmail());
        }
    }

    /**
     * Chaîne des causes (classes) et lieu de levée de chacune, sans aucun message : un message d'exception peut
     * contenir l'e-mail ou une valeur saisie, qui ne doivent jamais figurer dans un log.
     */
    static String diagnostic(Throwable error) {
        StringBuilder detail = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
            if (!detail.isEmpty()) {
                detail.append(" <- ");
            }
            detail.append(t.getClass().getName());
            StackTraceElement[] frames = t.getStackTrace();
            if (frames.length > 0) {
                detail.append(" at ").append(frames[0]);
            }
        }
        return detail.toString();
    }

    private String maskedEmail() {
        try {
            return Email.of(properties.email()).masked();
        } catch (InvalidEmailException e) {
            return "***";
        }
    }
}
