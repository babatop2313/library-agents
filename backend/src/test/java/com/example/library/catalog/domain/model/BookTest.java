package com.example.library.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.example.library.catalog.domain.exception.InvalidBookException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** RM-CAT-05 a RM-CAT-10 : regles de l'agregat Book. */
class BookTest {

    private static final Isbn ISBN = Isbn.of("9782070368228");

    private static List<FieldViolation> violationsOf(String title, String author, int copies) {
        try {
            Book.register(ISBN, title, author, copies);
        } catch (InvalidBookException e) {
            return e.violations();
        }
        throw new AssertionError("InvalidBookException attendue");
    }

    @Test
    void register_quandDonneesValides_alorsTousExemplairesDisponibles() {
        // Given / When
        Book book = Book.register(ISBN, "L'Étranger", "Albert Camus", 3);
        // Then
        assertThat(book.getId()).isNotNull();
        assertThat(book.getIsbn()).isEqualTo(ISBN);
        assertThat(book.getTitle()).isEqualTo("L'Étranger");
        assertThat(book.getAuthor()).isEqualTo("Albert Camus");
        assertThat(book.getTotalCopies()).isEqualTo(3);
        assertThat(book.getAvailableCopies()).isEqualTo(3);
    }

    @Test
    void register_quandDeuxAppels_alorsIdentifiantsDifferents() {
        // Given / When
        Book first = Book.register(ISBN, "A", "B", 1);
        Book second = Book.register(ISBN, "A", "B", 1);
        // Then
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void register_quandEspacesAutour_alorsTitreEtAuteurRognes() {
        // Given / When
        Book book = Book.register(ISBN, "  L'Étranger  ", "  Albert Camus ", 1);
        // Then
        assertThat(book.getTitle()).isEqualTo("L'Étranger");
        assertThat(book.getAuthor()).isEqualTo("Albert Camus");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1000})
    void register_quandBornesValides_alorsAccepte(int copies) {
        // When
        Book book = Book.register(ISBN, "T", "A", copies);
        // Then
        assertThat(book.getTotalCopies()).isEqualTo(copies);
        assertThat(book.getAvailableCopies()).isEqualTo(copies);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1001})
    void register_quandCopiesHorsBornes_alorsOutOfRange(int copies) {
        // When
        List<FieldViolation> violations = violationsOf("T", "A", copies);
        // Then
        assertThat(violations).containsExactly(new FieldViolation("copies", ViolationCode.OUT_OF_RANGE));
    }

    @Test
    void register_quandTitreAvec255Caracteres_alorsAccepte() {
        // Given
        String title = "a".repeat(255);
        // When
        Book book = Book.register(ISBN, title, "A", 1);
        // Then
        assertThat(book.getTitle()).hasSize(255);
    }

    @Test
    void register_quandTitreAvec255CaracteresPlusEspaces_alorsLongueurMesureeApresTrim() {
        // Given
        String title = "  " + "a".repeat(255) + "  ";
        // When
        Book book = Book.register(ISBN, title, "A", 1);
        // Then
        assertThat(book.getTitle()).hasSize(255);
    }

    @Test
    void register_quandTitreAvec256Caracteres_alorsTooLong() {
        // When
        List<FieldViolation> violations = violationsOf("a".repeat(256), "A", 1);
        // Then
        assertThat(violations).containsExactly(new FieldViolation("title", ViolationCode.TOO_LONG));
    }

    @Test
    void register_quandAuteurAvec256Caracteres_alorsTooLong() {
        // When
        List<FieldViolation> violations = violationsOf("T", "a".repeat(256), 1);
        // Then
        assertThat(violations).containsExactly(new FieldViolation("author", ViolationCode.TOO_LONG));
    }

    @Test
    void register_quandTitreVide_alorsBlank() {
        assertThat(violationsOf("", "A", 1)).containsExactly(new FieldViolation("title", ViolationCode.BLANK));
    }

    @Test
    void register_quandTitreEspacesSeulement_alorsBlank() {
        assertThat(violationsOf("   ", "A", 1)).containsExactly(new FieldViolation("title", ViolationCode.BLANK));
    }

    @Test
    void register_quandTitreNull_alorsRequired() {
        assertThat(violationsOf(null, "A", 1)).containsExactly(new FieldViolation("title", ViolationCode.REQUIRED));
    }

    @Test
    void register_quandAuteurVide_alorsBlank() {
        assertThat(violationsOf("T", "", 1)).containsExactly(new FieldViolation("author", ViolationCode.BLANK));
    }

    @Test
    void register_quandAuteurEspacesSeulement_alorsBlank() {
        assertThat(violationsOf("T", "   ", 1)).containsExactly(new FieldViolation("author", ViolationCode.BLANK));
    }

    @Test
    void register_quandAuteurNull_alorsRequired() {
        assertThat(violationsOf("T", null, 1)).containsExactly(new FieldViolation("author", ViolationCode.REQUIRED));
    }

    @Test
    void register_quandPlusieursViolations_alorsToutesRapporteesEnsemble() {
        // Given : titre vide, auteur blanc, copies 0
        // When
        List<FieldViolation> violations = violationsOf("", "   ", 0);
        // Then
        assertThat(violations).containsExactlyInAnyOrder(
                new FieldViolation("title", ViolationCode.BLANK),
                new FieldViolation("author", ViolationCode.BLANK),
                new FieldViolation("copies", ViolationCode.OUT_OF_RANGE));
    }

    @Test
    void register_quandViolation_alorsCodeValidationFailed() {
        // When / Then
        assertThatThrownBy(() -> Book.register(ISBN, "", "A", 1))
                .isInstanceOf(InvalidBookException.class)
                .extracting("code").isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void register_quandIsbnNull_alorsNullPointerException() {
        assertThatThrownBy(() -> Book.register(null, "T", "A", 1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void restore_quandDonneesIncoherentes_alorsAucuneRegleRejouee() {
        // Given
        BookId id = BookId.newId();
        // When : titre vide et 0 exemplaire, restore ne rejoue aucune regle
        Book book = Book.restore(id, ISBN, "", "", 0, 0);
        // Then
        assertThat(book.getId()).isEqualTo(id);
        assertThat(book.getTotalCopies()).isZero();
    }

    @Test
    void restore_quandExemplairesEmpruntes_alorsDisponiblesConserves() {
        // When
        Book book = Book.restore(BookId.newId(), ISBN, "T", "A", 5, 2);
        // Then
        assertThat(book.getTotalCopies()).isEqualTo(5);
        assertThat(book.getAvailableCopies()).isEqualTo(2);
    }

    @Test
    void isAvailable_quandZeroExemplaireDisponible_alorsFaux() {
        // Given
        Book book = Book.restore(BookId.newId(), ISBN, "T", "A", 2, 0);
        // When / Then
        assertThat(book.isAvailable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 1000})
    void isAvailable_quandAuMoinsUnExemplaireDisponible_alorsVrai(int available) {
        // Given
        Book book = Book.restore(BookId.newId(), ISBN, "T", "A", 1000, available);
        // When / Then
        assertThat(book.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_quandLivreNeuf_alorsVrai() {
        // Given / When
        Book book = Book.register(ISBN, "T", "A", 1);
        // Then
        assertThat(book.isAvailable()).isTrue();
    }

    @Test
    void bookId_quandNull_alorsNullPointerException() {
        assertThatThrownBy(() -> new BookId(null)).isInstanceOf(NullPointerException.class);
    }
}
