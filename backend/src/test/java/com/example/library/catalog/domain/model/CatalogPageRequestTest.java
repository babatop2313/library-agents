package com.example.library.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.library.catalog.domain.exception.InvalidCatalogSearchException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** RM-CAT-15 : page >= 0, taille de 1 à 50 (10 par défaut), sans correction silencieuse. */
class CatalogPageRequestTest {

    @Test
    void of_quandValeursAbsentes_alorsPageZeroEtTailleDixParDefaut() {
        // Given / When
        CatalogPageRequest request = CatalogPageRequest.of(null, null);
        // Then
        assertThat(request.page()).isZero();
        assertThat(request.size()).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 49, 50})
    void new_quandTailleDansLesBornes_alorsAcceptee(int size) {
        // Given / When / Then
        assertThat(new CatalogPageRequest(0, size).size()).isEqualTo(size);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 51, 1000})
    void new_quandTailleHorsBornes_alorsViolationOutOfRangeSurSize(int size) {
        // Given / When / Then
        assertThatThrownBy(() -> new CatalogPageRequest(0, size))
                .isInstanceOfSatisfying(InvalidCatalogSearchException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("size", ViolationCode.OUT_OF_RANGE)));
    }

    @Test
    void new_quandPageNegative_alorsViolationOutOfRangeSurPage() {
        // Given / When / Then
        assertThatThrownBy(() -> new CatalogPageRequest(-1, 10))
                .isInstanceOfSatisfying(InvalidCatalogSearchException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("page", ViolationCode.OUT_OF_RANGE)));
    }

    @Test
    void new_quandPageEtTailleInvalides_alorsLesDeuxViolations() {
        // Given / When / Then
        assertThatThrownBy(() -> new CatalogPageRequest(-3, 0))
                .isInstanceOfSatisfying(InvalidCatalogSearchException.class, e -> assertThat(e.violations())
                        .extracting(FieldViolation::field).containsExactly("page", "size"));
    }

    @Test
    void new_quandPageZero_alorsAccepteeEtOffsetZero() {
        // Given / When / Then
        assertThat(new CatalogPageRequest(0, 1).offset()).isZero();
    }

    @Test
    void violationsOf_quandValeursAbsentes_alorsAucuneViolation() {
        // Given / When / Then
        assertThat(CatalogPageRequest.violationsOf(null, null)).isEmpty();
    }

    @Test
    void offset_quandPageEtTailleGrandes_alorsCalculeEnLongSansDebordement() {
        // Given
        CatalogPageRequest request = new CatalogPageRequest(Integer.MAX_VALUE, 50);
        // When / Then
        assertThat(request.offset()).isEqualTo((long) Integer.MAX_VALUE * 50);
        assertThat(request.endExclusive()).isEqualTo((long) Integer.MAX_VALUE * 50 + 50);
    }

    @Test
    void endExclusive_quandPageDeuxTailleDix_alorsTrente() {
        // Given / When / Then
        assertThat(new CatalogPageRequest(2, 10).endExclusive()).isEqualTo(30);
    }
}
