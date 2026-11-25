package com.example.library.loan.adapter.in.web;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Corps de {@code POST /api/v1/loans}. Un champ {@code memberId} éventuel est ignoré : le membre vient du JWT. */
record BorrowBookRequest(@NotNull UUID bookId) {
}
