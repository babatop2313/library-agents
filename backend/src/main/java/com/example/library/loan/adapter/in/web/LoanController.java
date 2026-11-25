package com.example.library.loan.adapter.in.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.example.library.loan.application.port.in.BorrowBookUseCase;
import com.example.library.loan.application.port.in.ListCurrentLoansQuery;
import com.example.library.loan.application.port.in.ListCurrentLoansUseCase;
import com.example.library.loan.application.port.in.ReturnLoanCommand;
import com.example.library.loan.application.port.in.ReturnLoanUseCase;
import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.LoanId;
import com.example.library.loan.domain.model.MemberId;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Emprunts du membre connecté. Le membre vient toujours du JWT ({@code @CurrentMember}, placé en premier paramètre :
 * résolu avant le corps et le chemin), jamais de la requête.
 */
@RestController
@RequestMapping("/api/v1/loans")
class LoanController {

    private final BorrowBookUseCase borrowBookUseCase;
    private final ListCurrentLoansUseCase listCurrentLoansUseCase;
    private final ReturnLoanUseCase returnLoanUseCase;

    LoanController(BorrowBookUseCase borrowBookUseCase, ListCurrentLoansUseCase listCurrentLoansUseCase,
                   ReturnLoanUseCase returnLoanUseCase) {
        this.borrowBookUseCase = borrowBookUseCase;
        this.listCurrentLoansUseCase = listCurrentLoansUseCase;
        this.returnLoanUseCase = returnLoanUseCase;
    }

    @PostMapping
    @PreAuthorize("hasRole('MEMBER')")
    ResponseEntity<LoanCreatedResponse> borrow(@CurrentMember MemberId member,
                                               @Valid @RequestBody BorrowBookRequest request) {
        Loan loan = borrowBookUseCase.borrow(LoanWebMapper.toCommand(member, request));
        return ResponseEntity.created(URI.create("/api/v1/loans/" + loan.getId().value()))
                .body(LoanWebMapper.toCreatedResponse(loan));
    }

    @GetMapping
    @PreAuthorize("hasRole('MEMBER')")
    ResponseEntity<List<CurrentLoanResponse>> listCurrent(@CurrentMember MemberId member) {
        List<CurrentLoanResponse> body = listCurrentLoansUseCase.listCurrentLoans(new ListCurrentLoansQuery(member))
                .stream()
                .map(LoanWebMapper::toResponse)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasRole('MEMBER')")
    ResponseEntity<Void> giveBack(@CurrentMember MemberId member, @PathVariable("id") UUID id) {
        returnLoanUseCase.giveBack(new ReturnLoanCommand(member, new LoanId(id)));
        return ResponseEntity.noContent().build();
    }
}
