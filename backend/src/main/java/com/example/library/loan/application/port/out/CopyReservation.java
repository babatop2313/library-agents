package com.example.library.loan.application.port.out;

/** Résultat d'une demande de prise d'exemplaire auprès du catalogue. */
public enum CopyReservation {
    RESERVED,
    BOOK_NOT_FOUND,
    NO_COPY_AVAILABLE
}
