package com.example.library.catalog.application.port.in;

public interface ReserveCopyUseCase {

    /** RM-04 : prend un exemplaire disponible du livre (appelé par le module d'emprunts, via {@code catalog.api}). */
    CopyReservationOutcome reserve(ReserveCopyCommand command);
}
