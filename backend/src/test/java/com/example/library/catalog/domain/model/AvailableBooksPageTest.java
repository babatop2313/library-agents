package com.example.library.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RM-CAT-18, RM-CAT-19, RM-CAT-20 : totaux d'une page du catalogue. */
class AvailableBooksPageTest {

    private static AvailableBooksPage pageOf(long total, int size) {
        return new AvailableBooksPage(List.of(), new CatalogPageRequest(0, size), total);
    }

    @ParameterizedTest
    @CsvSource({"0,10,0", "1,10,1", "10,10,1", "11,10,2", "25,10,3", "30,10,3", "31,10,4", "50,50,1", "51,50,2"})
    void totalPages_quandTotalEtTaille_alorsPlafondDeLaDivision(long total, int size, int expected) {
        // Given / When / Then
        assertThat(pageOf(total, size).totalPages()).isEqualTo(expected);
    }

    @Test
    void empty_quandAucunResultat_alorsContenuVideEtZeroPage() {
        // Given / When
        AvailableBooksPage page = AvailableBooksPage.empty(new CatalogPageRequest(0, 10), 0);
        // Then
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
    }

    @Test
    void empty_quandPageAuDelaDeLaDerniere_alorsTotauxConserves() {
        // Given / When
        AvailableBooksPage page = AvailableBooksPage.empty(new CatalogPageRequest(9, 10), 25);
        // Then
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(25);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void new_quandListeSourceModifieeEnsuite_alorsContenuIndependantEtImmuable() {
        // Given
        Book book = Book.register(Isbn.of("9782070368228"), "T", "A", 1);
        List<Book> source = new ArrayList<>(List.of(book));
        AvailableBooksPage page = new AvailableBooksPage(source, new CatalogPageRequest(0, 10), 1);
        // When
        source.clear();
        // Then
        assertThat(page.content()).containsExactly(book);
        assertThatThrownBy(() -> page.content().add(book)).isInstanceOf(UnsupportedOperationException.class);
    }
}
