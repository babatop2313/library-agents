package com.example.library.catalog.domain.exception;

/** Limite de résultats hors des bornes autorisées pour la liste des livres disponibles. */
public class LimitOutOfRangeException extends DomainException {

    private final int requested;
    private final int min;
    private final int max;

    public LimitOutOfRangeException(int requested, int min, int max) {
        super("LIMIT_OUT_OF_RANGE", "La limite doit être comprise entre " + min + " et " + max);
        this.requested = requested;
        this.min = min;
        this.max = max;
    }

    public int getRequested() { return requested; }
    public int getMin() { return min; }
    public int getMax() { return max; }
}
