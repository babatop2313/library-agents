package com.example.library.catalog.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.example.library.catalog.domain.model.AvailableBooksLimit;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.Test;

/** RM-04, RM-MCP-06, RM-MCP-08 : filtre, tri deterministe et troncature. */
class AvailableBooksSelectionTest {

    private final AvailableBooksSelection selection = new AvailableBooksSelection();

    private static Book book(String isbn, String title, int total, int available) {
        return Book.restore(BookId.newId(), Isbn.of(isbn), title, "Auteur", total, available);
    }

    private static List<String> isbns(List<Book> books) {
        return books.stream().map(b -> b.getIsbn().value()).toList();
    }

    @Test
    void select_quandLivresEpuises_alorsExclus() {
        // Given
        Book available = book("9782070368228", "Dispo", 3, 1);
        Book exhausted = book("9782070368235", "Epuise", 2, 0);
        // When
        List<Book> result = selection.select(List.of(exhausted, available), new AvailableBooksLimit(10));
        // Then
        assertThat(result).containsExactly(available);
    }

    @Test
    void select_quandCasseMixte_alorsTriInsensibleALaCasse() {
        // Given
        Book b = book("9782070368228", "banane", 1, 1);
        Book a = book("9782070368235", "Zebre", 1, 1);
        Book c = book("9782070368242", "Abricot", 1, 1);
        // When
        List<Book> result = selection.select(List.of(a, b, c), new AvailableBooksLimit(10));
        // Then : Abricot, banane, Zebre
        assertThat(result).containsExactly(c, b, a);
    }

    @Test
    void select_quandMemeTitreAUneCasseDifferente_alorsDepartageParIsbn() {
        // Given
        Book second = book("9782070368242", "ROMAN", 1, 1);
        Book first = book("9782070368228", "roman", 1, 1);
        Book third = book("9782070368259", "Roman", 1, 1);
        // When
        List<Book> result = selection.select(List.of(second, third, first), new AvailableBooksLimit(10));
        // Then
        assertThat(isbns(result)).containsExactly("9782070368228", "9782070368242", "9782070368259");
    }

    @Test
    void select_quandPlusDeLivresQueLaLimite_alorsTronqueApresTri() {
        // Given : 5 livres disponibles, limite 3
        List<Book> candidates = new ArrayList<>();
        candidates.add(book("9782070368228", "E", 1, 1));
        candidates.add(book("9782070368235", "D", 1, 1));
        candidates.add(book("9782070368242", "C", 1, 1));
        candidates.add(book("9782070368259", "B", 1, 1));
        candidates.add(book("9782070368266", "A", 1, 1));
        // When
        List<Book> result = selection.select(candidates, new AvailableBooksLimit(3));
        // Then : les 3 premiers dans l'ordre du catalogue
        assertThat(result).extracting(Book::getTitle).containsExactly("A", "B", "C");
    }

    @Test
    void select_quandLimiteSuperieureAuNombre_alorsTousRenvoyes() {
        // Given
        List<Book> candidates = List.of(book("9782070368228", "A", 1, 1), book("9782070368235", "B", 1, 1));
        // When
        List<Book> result = selection.select(candidates, new AvailableBooksLimit(20));
        // Then
        assertThat(result).hasSize(2);
    }

    @Test
    void select_quandEntreeVide_alorsListeVide() {
        // When
        List<Book> result = selection.select(List.of(), new AvailableBooksLimit(10));
        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void select_quandAucunDisponible_alorsListeVide() {
        // Given
        List<Book> candidates = List.of(book("9782070368228", "A", 2, 0));
        // When
        List<Book> result = selection.select(candidates, new AvailableBooksLimit(10));
        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void select_quandAppliqueDeuxFois_alorsIdempotent() {
        // Given
        List<Book> candidates = List.of(book("9782070368235", "B", 1, 1), book("9782070368228", "A", 1, 1),
                book("9782070368242", "C", 1, 0));
        AvailableBooksLimit limit = new AvailableBooksLimit(10);
        // When
        List<Book> once = selection.select(candidates, limit);
        List<Book> twice = selection.select(once, limit);
        // Then
        assertThat(twice).isEqualTo(once);
    }
}
