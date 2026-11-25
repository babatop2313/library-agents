-- Module loan : emprunts (RM-01 à RM-05, RM-LOAN-28). member_id et book_id sans clé étrangère :
-- app_user (identity) et book (catalog) appartiennent à d'autres modules.
CREATE TABLE loan (
    id          UUID NOT NULL,
    member_id   UUID NOT NULL,
    book_id     UUID NOT NULL,
    borrowed_on DATE NOT NULL,
    due_on      DATE NOT NULL,
    returned_on DATE,
    CONSTRAINT pk_loan PRIMARY KEY (id),
    CONSTRAINT ck_loan_due_on_not_before_borrowed_on CHECK (due_on >= borrowed_on),
    CONSTRAINT ck_loan_returned_on_not_before_borrowed_on CHECK (returned_on IS NULL OR returned_on >= borrowed_on)
);

-- RM-LOAN-28 : un seul emprunt actif par membre et par livre (filet de sécurité de LoanPolicy).
-- Sert aussi aux lectures des emprunts actifs d'un membre (member_id en tête, même prédicat) :
-- pas d'index ix_loan_member_active séparé (décision D7).
CREATE UNIQUE INDEX uk_loan_member_book_active ON loan (member_id, book_id) WHERE returned_on IS NULL;
