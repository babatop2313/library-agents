package com.example.library.loan.domain.model;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Objects;

import com.example.library.loan.domain.exception.LoanAlreadyReturnedException;

/** Emprunt d'un livre par un membre. Aucun setter : l'état ne change que par des méthodes métier. */
public final class Loan {

    /** RM-03 : un emprunt dure 14 jours. */
    public static final int LOAN_DURATION_DAYS = 14;

    /**
     * Ordre d'affichage : échéance croissante, puis identifiant. La comparaison des chaînes hexadécimales en
     * minuscules reproduit l'ordre du type {@code uuid} de PostgreSQL ; {@link java.util.UUID#compareTo} est signé
     * et ne le reproduit pas.
     */
    public static final Comparator<Loan> DISPLAY_ORDER = Comparator
            .comparing(Loan::getDueOn)
            .thenComparing(loan -> loan.getId().value().toString());

    private final LoanId id;
    private final MemberId memberId;
    private final BookId bookId;
    private final LocalDate borrowedOn;
    private final LocalDate dueOn;
    private LocalDate returnedOn;

    private Loan(LoanId id, MemberId memberId, BookId bookId,
                 LocalDate borrowedOn, LocalDate dueOn, LocalDate returnedOn) {
        this.id = Objects.requireNonNull(id, "id");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.bookId = Objects.requireNonNull(bookId, "bookId");
        this.borrowedOn = Objects.requireNonNull(borrowedOn, "borrowedOn");
        this.dueOn = Objects.requireNonNull(dueOn, "dueOn");
        this.returnedOn = returnedOn;
    }

    /** Création d'un nouvel emprunt : échéance à J+14 (RM-03). */
    public static Loan open(MemberId memberId, BookId bookId, LocalDate today) {
        Objects.requireNonNull(today, "today");
        return new Loan(LoanId.newId(), memberId, bookId, today, today.plusDays(LOAN_DURATION_DAYS), null);
    }

    /** Reconstitution depuis la persistance : aucune règle rejouée. */
    public static Loan restore(LoanId id, MemberId memberId, BookId bookId,
                               LocalDate borrowedOn, LocalDate dueOn, LocalDate returnedOn) {
        return new Loan(id, memberId, bookId, borrowedOn, dueOn, returnedOn);
    }

    /** RM-05 : on ne rend pas deux fois le même emprunt. Un emprunt en retard se rend normalement. */
    public void giveBack(LocalDate today) {
        Objects.requireNonNull(today, "today");
        if (returnedOn != null) {
            throw new LoanAlreadyReturnedException(id);
        }
        this.returnedOn = today;
    }

    public boolean isActive() {
        return returnedOn == null;
    }

    /** RM-02 : en retard = actif et date du jour strictement postérieure à l'échéance. */
    public boolean isOverdue(LocalDate today) {
        return isActive() && today.isAfter(dueOn);
    }

    public boolean concerns(BookId book) {
        return bookId.equals(book);
    }

    public LoanId getId() { return id; }
    public MemberId getMemberId() { return memberId; }
    public BookId getBookId() { return bookId; }
    public LocalDate getBorrowedOn() { return borrowedOn; }
    public LocalDate getDueOn() { return dueOn; }
    public LocalDate getReturnedOn() { return returnedOn; }
}
