package com.example.library.loan.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identifiant du membre qui emprunte (le {@code sub} du JWT). */
public record MemberId(UUID value) {

    public MemberId {
        Objects.requireNonNull(value, "value");
    }
}
