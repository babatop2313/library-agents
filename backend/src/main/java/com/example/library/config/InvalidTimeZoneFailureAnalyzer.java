package com.example.library.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Présente proprement l'échec de démarrage dû à un fuseau horaire invalide. */
public class InvalidTimeZoneFailureAnalyzer extends AbstractFailureAnalyzer<InvalidTimeZoneException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidTimeZoneException cause) {
        return new FailureAnalysis(
                "app.timezone (APP_TIMEZONE) invalide : \"" + cause.value() + "\".",
                "Indiquez un identifiant de fuseau IANA, par exemple Africa/Dakar ou Europe/Paris, "
                        + "dans APP_TIMEZONE (fichier .env, voir .env.example), ou retirez la variable "
                        + "pour utiliser Africa/Dakar.",
                cause);
    }
}
