package com.example.library.catalog.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.Test;

/** Decision 10 : exception du domaine, sans notion HTTP. */
class BookNotFoundExceptionTest {

    @Test
    void constructeur_quandIsbnFourni_alorsCodeEtIsbnConserves() {
        // Given
        Isbn isbn = Isbn.of("9780000000002");
        // When
        BookNotFoundException e = new BookNotFoundException(isbn);
        // Then
        assertThat(e).isInstanceOf(DomainException.class);
        assertThat(e.getCode()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(e.getIsbn()).isEqualTo(isbn);
        assertThat(e.getMessage()).isEqualTo("Aucun livre trouvé pour cet ISBN");
    }

    @Test
    void constructeur_quandIsbnNull_alorsNullPointerException() {
        // When / Then
        assertThatThrownBy(() -> new BookNotFoundException(null)).isInstanceOf(NullPointerException.class);
    }
}
