package com.example.library.loan.adapter.in.web;

import java.time.LocalDate;
import java.util.UUID;

record LoanCreatedResponse(UUID id, UUID bookId, LocalDate borrowedOn, LocalDate dueOn) {
}
