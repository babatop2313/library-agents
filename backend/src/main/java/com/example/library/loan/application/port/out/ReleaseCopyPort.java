package com.example.library.loan.application.port.out;

import com.example.library.loan.domain.model.BookId;

public interface ReleaseCopyPort {

    /** Restitue un exemplaire du livre, dans la transaction en cours. */
    CopyRelease release(BookId bookId);
}
