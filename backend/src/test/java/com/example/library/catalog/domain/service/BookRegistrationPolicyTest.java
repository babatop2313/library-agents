package com.example.library.catalog.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.library.catalog.domain.exception.IsbnAlreadyExistsException;
import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.Test;

/** RM-07 / RM-CAT-04 : unicite de l'ISBN. */
class BookRegistrationPolicyTest {

    private final BookRegistrationPolicy policy = new BookRegistrationPolicy();
    private final Isbn isbn = Isbn.of("9782070368228");

    @Test
    void ensureIsbnIsFree_quandIsbnDejaEnregistre_alorsIsbnAlreadyExistsException() {
        // When / Then
        assertThatThrownBy(() -> policy.ensureIsbnIsFree(isbn, true))
                .isInstanceOfSatisfying(IsbnAlreadyExistsException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("ISBN_ALREADY_EXISTS");
                    assertThat(e.getIsbn()).isEqualTo(isbn);
                });
    }

    @Test
    void ensureIsbnIsFree_quandIsbnLibre_alorsAucuneException() {
        // When / Then
        assertThatCode(() -> policy.ensureIsbnIsFree(isbn, false)).doesNotThrowAnyException();
    }
}
