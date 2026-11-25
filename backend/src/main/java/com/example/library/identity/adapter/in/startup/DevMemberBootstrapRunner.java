package com.example.library.identity.adapter.in.startup;

import com.example.library.identity.application.port.in.BootstrapDevMemberCommand;
import com.example.library.identity.application.port.in.BootstrapDevMemberUseCase;
import com.example.library.identity.application.port.in.DevMemberBootstrapResult;
import com.example.library.identity.config.DevMemberProperties;
import com.example.library.identity.domain.exception.InvalidEmailException;
import com.example.library.identity.domain.model.Email;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * RM-LOAN-30 : crée un membre de test au démarrage, <b>uniquement avec le profil local</b>. N'appelle que le port
 * d'entrée et ne fait jamais échouer le démarrage. Messages figés : ni le mot de passe, ni sa longueur, ni la
 * commande (toString) n'y figurent ; l'e-mail n'apparaît que masqué, dans le seul WARN qui n'a pas d'identifiant
 * utilisateur.
 */
@Component
@Profile("local")
class DevMemberBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevMemberBootstrapRunner.class);

    private final BootstrapDevMemberUseCase bootstrapDevMemberUseCase;
    private final DevMemberProperties properties;

    DevMemberBootstrapRunner(BootstrapDevMemberUseCase bootstrapDevMemberUseCase, DevMemberProperties properties) {
        this.bootstrapDevMemberUseCase = bootstrapDevMemberUseCase;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            DevMemberBootstrapResult result = bootstrapDevMemberUseCase.bootstrap(
                    new BootstrapDevMemberCommand(properties.email(), properties.password()));
            report(result);
        } catch (DataIntegrityViolationException e) {
            // création concurrente : le message du SGBD peut contenir l'e-mail, on ne le journalise pas
            log.warn("Amorçage du membre de test ignoré : conflit d'intégrité (création concurrente probable)");
        } catch (RuntimeException e) {
            log.warn("Amorçage du membre de test en échec ({}) : le démarrage continue",
                    e.getClass().getSimpleName());
            if (log.isDebugEnabled()) {
                log.debug("Détail de l'échec d'amorçage du membre de test : {}",
                        LibrarianBootstrapRunner.diagnostic(e));
            }
        }
    }

    private void report(DevMemberBootstrapResult result) {
        switch (result.outcome()) {
            case CREATED -> log.info("Membre de test créé : userId={}", result.createdUserId().value());
            case ALREADY_PRESENT -> log.debug("Membre de test déjà présent : aucun amorçage");
            case MISSING_CONFIGURATION -> log.warn(
                    "DEV_MEMBER_EMAIL ou DEV_MEMBER_PASSWORD absent : aucun membre de test créé");
            case INVALID_EMAIL -> log.warn(
                    "DEV_MEMBER_EMAIL n'est pas une adresse e-mail valide : aucun membre de test créé");
            case WEAK_PASSWORD -> log.warn("DEV_MEMBER_PASSWORD ne respecte pas la politique "
                    + "(12 caractères minimum, 72 octets maximum) : aucun membre de test créé");
            case EMAIL_TAKEN_WITHOUT_MEMBER_ROLE -> log.warn(
                    "L'e-mail du membre de test {} appartient déjà à un utilisateur sans le rôle MEMBER : "
                            + "aucun membre de test créé, utilisateur inchangé", maskedEmail());
        }
    }

    private String maskedEmail() {
        try {
            return Email.of(properties.email()).masked();
        } catch (InvalidEmailException e) {
            return "***";
        }
    }
}
