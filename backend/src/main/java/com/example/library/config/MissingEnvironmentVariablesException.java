package com.example.library.config;

import java.util.List;

/** Levée au démarrage lorsque des variables d'environnement obligatoires (secrets) ne sont pas définies. */
public class MissingEnvironmentVariablesException extends RuntimeException {

    private final List<String> missing;

    public MissingEnvironmentVariablesException(List<String> missing) {
        super("Variables d'environnement obligatoires manquantes : " + String.join(", ", missing) + ".");
        this.missing = List.copyOf(missing);
    }

    /** Noms des variables manquantes, dans l'ordre de déclaration. */
    public List<String> missing() {
        return missing;
    }
}
