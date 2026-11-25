package com.example.library.loan.application.port.out;

import com.example.library.loan.domain.model.BookId;

public interface ReserveCopyPort {

    /** Prend un exemplaire disponible du livre, dans la transaction en cours (RM-04). */
    CopyReservation reserve(BookId bookId);
}
