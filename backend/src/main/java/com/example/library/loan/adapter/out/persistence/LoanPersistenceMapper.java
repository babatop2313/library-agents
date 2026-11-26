package com.example.library.loan.adapter.out.persistence;

import com.example.library.loan.domain.model.BookId;
import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.LoanId;
import com.example.library.loan.domain.model.MemberId;
import org.springframework.stereotype.Component;

@Component
class LoanPersistenceMapper {

    LoanJpaEntity toJpa(Loan loan) {
        return new LoanJpaEntity(loan.getId().value(), loan.getMemberId().value(), loan.getBookId().value(),
                loan.getBorrowedOn(), loan.getDueOn(), loan.getReturnedOn());
    }

    Loan toDomain(LoanJpaEntity entity) {
        return Loan.restore(new LoanId(entity.getId()), new MemberId(entity.getMemberId()),
                new BookId(entity.getBookId()), entity.getBorrowedOn(), entity.getDueOn(), entity.getReturnedOn());
    }
}
