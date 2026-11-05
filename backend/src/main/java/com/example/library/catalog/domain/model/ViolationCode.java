package com.example.library.catalog.domain.model;

/** Nature d'une violation de règle de saisie sur un champ. */
public enum ViolationCode {
    REQUIRED,
    BLANK,
    TOO_LONG,
    INVALID_FORMAT,
    OUT_OF_RANGE
}
