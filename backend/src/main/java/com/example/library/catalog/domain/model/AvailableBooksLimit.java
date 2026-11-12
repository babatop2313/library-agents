package com.example.library.catalog.domain.model;

import com.example.library.catalog.domain.exception.LimitOutOfRangeException;

/** Nombre maximal de livres renvoyés par la liste des livres disponibles : 1 à 20, 10 par défaut. */
public record AvailableBooksLimit(int value) {

    public static final int MIN = 1;
    public static final int MAX = 20;
    public static final int DEFAULT = 10;

    public AvailableBooksLimit {
        if (value < MIN || value > MAX) {
            throw new LimitOutOfRangeException(value, MIN, MAX);
        }
    }

    /** Valeur par défaut si la limite n'est pas fournie, sinon validation des bornes. */
    public static AvailableBooksLimit of(Integer requested) {
        return new AvailableBooksLimit(requested == null ? DEFAULT : requested);
    }
}
