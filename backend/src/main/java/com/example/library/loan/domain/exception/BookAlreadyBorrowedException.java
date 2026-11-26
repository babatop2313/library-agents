package com.example.library.loan.domain.exception;

/** Le membre a déjà un emprunt actif de ce livre. */
public class BookAlreadyBorrowedException extends DomainException {

    public BookAlreadyBorrowedException() {
        super("BOOK_ALREADY_BORROWED", "Vous avez déjà un emprunt en cours pour ce livre");
    }
}
