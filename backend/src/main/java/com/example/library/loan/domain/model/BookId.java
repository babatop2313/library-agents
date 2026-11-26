package com.example.library.loan.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identifiant d'un livre du point de vue des emprunts (distinct de celui du catalogue : un module n'importe pas l'autre). */
public record BookId(UUID value) {

    public BookId {
        Objects.requireNonNull(value, "value");
    }
}
