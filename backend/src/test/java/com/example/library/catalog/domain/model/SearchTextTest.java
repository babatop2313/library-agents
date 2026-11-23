package com.example.library.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.library.catalog.domain.exception.InvalidCatalogSearchException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** RM-CAT-17 : texte rogné, 100 caractères maximum, sous-chaîne insensible à la casse du titre ou de l'auteur. */
class SearchTextTest {

    private static Book book(String isbn, String title, String author) {
        return Book.register(Isbn.of(isbn), title, author, 1);
    }

    private final Book etranger = book("9782070368228", "L'Étranger", "Albert Camus");
    private final Book germinal = book("9782070368235", "Germinal", "Émile Zola");

    @Test
    void of_quandNul_alorsAucunFiltre() {
        // Given / When / Then
        assertThat(SearchText.of(null)).isEqualTo(SearchText.NONE);
        assertThat(SearchText.of(null).isEmpty()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t "})
    void of_quandVideOuBlanc_alorsAucunFiltre(String raw) {
        // Given / When / Then
        assertThat(SearchText.of(raw).isEmpty()).isTrue();
    }

    @Test
    void of_quandEspacesAutour_alorsRogne() {
        // Given / When / Then
        assertThat(SearchText.of("  camus  ").value()).isEqualTo("camus");
    }

    @Test
    void of_quandCentCaracteres_alorsAcceptee() {
        // Given
        String hundred = "a".repeat(100);
        // When / Then
        assertThat(SearchText.of(hundred).value()).hasSize(100);
    }

    @Test
    void of_quandCentUnCaracteres_alorsViolationTooLongSurQ() {
        // Given
        String tooLong = "a".repeat(101);
        // When / Then
        assertThatThrownBy(() -> SearchText.of(tooLong))
                .isInstanceOfSatisfying(InvalidCatalogSearchException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("q", ViolationCode.TOO_LONG)));
    }

    @Test
    void of_quandCentCaracteresPlusEspaces_alorsLongueurMesureeApresTrim() {
        // Given
        String padded = "  " + "a".repeat(100) + "  ";
        // When / Then
        assertThat(SearchText.of(padded).value()).hasSize(100);
    }

    @Test
    void violationsOf_quandNul_alorsAucuneViolation() {
        // Given / When / Then
        assertThat(SearchText.violationsOf(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"camus", "CAMUS", "Camus", "alb", "étranger", "ÉTRANGER", "l'é"})
    void matches_quandSousChaineDuTitreOuDeLAuteurSansCasse_alorsVrai(String q) {
        // Given / When / Then
        assertThat(SearchText.of(q).matches(etranger)).isTrue();
    }

    @Test
    void matches_quandSousChaineDansLAuteurOuLeTitre_alorsVrai() {
        // Given / When / Then : OU logique entre titre et auteur
        assertThat(SearchText.of("zola").matches(germinal)).isTrue();
        assertThat(SearchText.of("germ").matches(germinal)).isTrue();
    }

    @Test
    void matches_quandAccentDifferent_alorsFaux() {
        // Given / When / Then : recherche sensible aux accents (décision Q5)
        assertThat(SearchText.of("etranger").matches(etranger)).isFalse();
    }

    @Test
    void matches_quandTexteSurLIsbn_alorsFaux() {
        // Given / When / Then : l'ISBN n'est pas cherché
        assertThat(SearchText.of("9782070368228").matches(etranger)).isFalse();
    }

    @Test
    void matches_quandAucunFiltre_alorsToujoursVrai() {
        // Given / When / Then
        assertThat(SearchText.NONE.matches(etranger)).isTrue();
    }

    @Test
    void matches_quandCaracteresSpeciauxLitteraux_alorsPasDeJokers() {
        // Given
        Book percent = book("9782070368242", "100% Python", "Auteur");
        Book plain = book("9782070368259", "Le Python", "Auteur");
        // When / Then
        assertThat(SearchText.of("100%").matches(percent)).isTrue();
        assertThat(SearchText.of("100%").matches(plain)).isFalse();
        assertThat(SearchText.of("%").matches(plain)).isFalse();
        assertThat(SearchText.of("_").matches(plain)).isFalse();
        assertThat(SearchText.of("\\").matches(plain)).isFalse();
    }

    @Test
    void new_quandValeurNulle_alorsNullPointerException() {
        // Given / When / Then
        assertThatThrownBy(() -> new SearchText(null)).isInstanceOf(NullPointerException.class);
    }
}
