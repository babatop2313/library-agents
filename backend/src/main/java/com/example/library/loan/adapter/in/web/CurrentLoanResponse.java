package com.example.library.loan.adapter.in.web;

import java.time.LocalDate;
import java.util.UUID;

/** Emprunt en cours ; {@code isbn}, {@code title} et {@code author} sont nuls (présents) si le livre n'existe plus. */
record CurrentLoanResponse(UUID id, UUID bookId, String isbn, String title, String author,
                           LocalDate borrowedOn, LocalDate dueOn, boolean overdue) {
}
