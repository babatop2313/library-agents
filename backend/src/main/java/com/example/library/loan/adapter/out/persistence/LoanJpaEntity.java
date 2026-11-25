package com.example.library.loan.adapter.out.persistence;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Entité JPA d'un emprunt. Implémente {@link Persistable} : l'identifiant est assigné par le domaine, et sans cela
 * Spring Data ferait un {@code merge} (donc un SELECT) au lieu d'un {@code persist}. Pas de colonne de version :
 * la concurrence est arbitrée par le verrou de ligne et les mises à jour conditionnelles.
 */
@Entity
@Table(name = "loan")
class LoanJpaEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "book_id", nullable = false)
    private UUID bookId;

    @Column(name = "borrowed_on", nullable = false)
    private LocalDate borrowedOn;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "returned_on")
    private LocalDate returnedOn;

    /** Vrai à la construction ; faux dès que l'entité vient de la base ou vient d'être insérée. */
    @Transient
    private boolean isNew = true;

    protected LoanJpaEntity() {
    }

    LoanJpaEntity(UUID id, UUID memberId, UUID bookId, LocalDate borrowedOn, LocalDate dueOn, LocalDate returnedOn) {
        this.id = id;
        this.memberId = memberId;
        this.bookId = bookId;
        this.borrowedOn = borrowedOn;
        this.dueOn = dueOn;
        this.returnedOn = returnedOn;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() { return id; }

    @Override
    public boolean isNew() { return isNew; }

    UUID getMemberId() { return memberId; }
    UUID getBookId() { return bookId; }
    LocalDate getBorrowedOn() { return borrowedOn; }
    LocalDate getDueOn() { return dueOn; }
    LocalDate getReturnedOn() { return returnedOn; }
}
