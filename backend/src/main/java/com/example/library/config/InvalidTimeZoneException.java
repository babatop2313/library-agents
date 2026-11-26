package com.example.library.config;

/** Levée au démarrage lorsque le fuseau horaire configuré n'est pas reconnu. La valeur n'est pas un secret. */
public class InvalidTimeZoneException extends RuntimeException {

    private final String property;
    private final String value;

    public InvalidTimeZoneException(String property, String value, Throwable cause) {
        super(property + " invalide : \"" + value + "\"", cause);
        this.property = property;
        this.value = value;
    }

    public String property() {
        return property;
    }

    public String value() {
        return value;
    }
}
