package com.example.library.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.library.catalog.domain.exception.InvalidCatalogSearchException;
import org.junit.jupiter.api.Test;

/** RM-CAT-15, RM-CAT-17 : consultation du catalogue, toutes les violations rapportées en une passe. */
class CatalogSearchTest {

    @Test
    void of_quandTousParametresAbsents_alorsDefauts() {
        // Given / When
        CatalogSearch search = CatalogSearch.of(null, null, null);
        // Then
        assertThat(search.text()).isEqualTo(SearchText.NONE);
        assertThat(search.pageRequest()).isEqualTo(new CatalogPageRequest(0, 10));
    }

    @Test
    void of_quandParametresValides_alorsConstruitAvecTexteRogne() {
        // Given / When
        CatalogSearch search = CatalogSearch.of(2, 20, "  hugo ");
        // Then
        assertThat(search.text().value()).isEqualTo("hugo");
        assertThat(search.pageRequest()).isEqualTo(new CatalogPageRequest(2, 20));
    }

    @Test
    void of_quandPageSizeEtQInvalides_alorsTroisViolationsEnUneSeuleException() {
        // Given / When / Then
        assertThatThrownBy(() -> CatalogSearch.of(-1, 0, "a".repeat(101)))
                .isInstanceOfSatisfying(InvalidCatalogSearchException.class, e -> {
                    assertThat(e.violations()).extracting(FieldViolation::field)
                            .containsExactlyInAnyOrder("page", "size", "q");
                    assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED");
                });
    }

    @Test
    void of_quandSeulQTropLong_alorsUneViolationSurQ() {
        // Given / When / Then
        assertThatThrownBy(() -> CatalogSearch.of(0, 10, "x".repeat(150)))
                .isInstanceOfSatisfying(InvalidCatalogSearchException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("q", ViolationCode.TOO_LONG)));
    }
}
