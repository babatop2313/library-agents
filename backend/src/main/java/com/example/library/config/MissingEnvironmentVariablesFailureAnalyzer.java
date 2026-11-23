package com.example.library.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Présente proprement l'échec de démarrage dû aux variables d'environnement manquantes. */
public class MissingEnvironmentVariablesFailureAnalyzer
        extends AbstractFailureAnalyzer<MissingEnvironmentVariablesException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingEnvironmentVariablesException cause) {
        return new FailureAnalysis(
                "Variables d'environnement obligatoires manquantes : " + String.join(", ", cause.missing()) + ".",
                "Définissez-les (par exemple dans le fichier .env, voir .env.example), sans valeur par défaut. "
                        + "JWT_SECRET doit faire au moins 32 octets.",
                cause);
    }
}
