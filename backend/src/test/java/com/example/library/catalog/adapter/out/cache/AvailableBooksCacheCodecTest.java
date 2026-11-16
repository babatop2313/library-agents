package com.example.library.catalog.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Codec JSON du cache (pur, sans Redis ni Spring) : RC-02, RC-08, RC-09, RC-11, RC-18, D3, D9, D12. */
class AvailableBooksCacheCodecTest {

    private static final String VALID_ID = "6f1b2c3d-0000-4000-8000-000000000001";
    private static final String VALID_ISBN = "9782070368228";

    private final AvailableBooksCacheCodec codec = new AvailableBooksCacheCodec();
    private final JsonMapper json = JsonMapper.builder().build();

    private static Book book(String isbn, String title, String author, int total, int available) {
        return Book.restore(new BookId(UUID.randomUUID()), Isbn.of(isbn), title, author, total, available);
    }

    private static String element(String id, String isbn, String title, String author, String total, String available) {
        return "{\"id\":%s,\"isbn\":%s,\"title\":%s,\"author\":%s,\"totalCopies\":%s,\"availableCopies\":%s}"
                .formatted(id, isbn, title, author, total, available);
    }

    private static String validElement() {
        return element("\"" + VALID_ID + "\"", "\"" + VALID_ISBN + "\"", "\"L'Étranger\"", "\"Albert Camus\"", "3", "2");
    }

    private static String array(String... elements) {
        return "[" + String.join(",", elements) + "]";
    }

    private void assertUnreadable(String value) {
        assertThatThrownBy(() -> codec.decode(value)).isInstanceOf(UnreadableCacheValueException.class);
    }

    @Test
    void encode_quandListeVide_alorsTableauJsonVide() {
        // Given
        List<Book> books = List.of();
        // When
        String encoded = codec.encode(books);
        // Then
        assertThat(encoded).isEqualTo("[]");
    }

    @Test
    void decode_quandTableauVide_alorsListeVideEtNonAbsence() {
        // Given : D3, une liste vide est un résultat valide
        // When
        List<Book> decoded = codec.decode("[]");
        // Then
        assertThat(decoded).isNotNull().isEmpty();
    }

    @Test
    void encodeDecode_quandTroisLivres_alorsMemesChampsMemeOrdreMemeId() {
        // Given : disponibles != total, pour vérifier que l'état est reconstitué et non recalculé (D9)
        List<Book> books = List.of(
                book("9782070368228", "Alpha", "Auteur A", 5, 2),
                book("9782070368235", "beta", "Auteur B", 1, 1),
                book("9782070368242", "Zèbre", "Auteur Z", 10, 7));
        // When
        List<Book> decoded = codec.decode(codec.encode(books));
        // Then
        assertThat(decoded).hasSize(3);
        for (int i = 0; i < books.size(); i++) {
            Book expected = books.get(i);
            Book actual = decoded.get(i);
            assertThat(actual.getId()).isEqualTo(expected.getId());
            assertThat(actual.getIsbn()).isEqualTo(expected.getIsbn());
            assertThat(actual.getTitle()).isEqualTo(expected.getTitle());
            assertThat(actual.getAuthor()).isEqualTo(expected.getAuthor());
            assertThat(actual.getTotalCopies()).isEqualTo(expected.getTotalCopies());
            assertThat(actual.getAvailableCopies()).isEqualTo(expected.getAvailableCopies());
        }
    }

    @Test
    void encode_quandLivres_alorsChampsExactsSansClasseNiTypage() {
        // Given
        List<Book> books = List.of(book("9782070368228", "L'Étranger", "Albert Camus", 3, 2));
        // When
        String encoded = codec.encode(books);
        // Then
        JsonNode root = json.readTree(encoded);
        assertThat(root.isArray()).isTrue();
        assertThat(root).hasSize(1);
        Set<String> fieldNames = new java.util.TreeSet<>(root.get(0).propertyNames());
        assertThat(fieldNames).containsExactly("author", "availableCopies", "id", "isbn", "title", "totalCopies");
        assertThat(encoded).doesNotContain("@class", "com.example", "Book", "Isbn");
        assertThat(root.get(0).get("id").asString()).isEqualTo(books.get(0).getId().value().toString());
        assertThat(root.get(0).get("isbn").asString()).isEqualTo("9782070368228");
        assertThat(root.get(0).get("availableCopies").asInt()).isEqualTo(2);
        assertThat(root.get(0).get("totalCopies").asInt()).isEqualTo(3);
    }

    @Test
    void decode_quandElementValide_alorsLivreReconstitue() {
        // Given
        String value = array(validElement());
        // When
        List<Book> decoded = codec.decode(value);
        // Then
        assertThat(decoded).singleElement().satisfies(book -> {
            assertThat(book.getId().value()).hasToString(VALID_ID);
            assertThat(book.getAvailableCopies()).isEqualTo(2);
            assertThat(book.getTotalCopies()).isEqualTo(3);
        });
    }

    @Test
    void decode_quandVingtElements_alorsAccepte() {
        // Given : la borne haute (D1) est autorisée
        List<String> elements = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            elements.add(element("\"" + UUID.randomUUID() + "\"", "\"978000000%04d\"".formatted(i), "\"Livre\"",
                    "\"Auteur\"", "1", "1"));
        }
        // When
        List<Book> decoded = codec.decode(array(elements.toArray(String[]::new)));
        // Then
        assertThat(decoded).hasSize(20);
    }

    @Test
    void decode_quandTexteNonJson_alorsUnreadableCacheValueException() {
        assertUnreadable("ceci n'est pas du json");
    }

    @Test
    void decode_quandLitteralNull_alorsUnreadableCacheValueException() {
        assertUnreadable("null");
    }

    @Test
    void decode_quandObjetAuLieuDeTableau_alorsUnreadableCacheValueException() {
        assertUnreadable("{\"foo\":1}");
    }

    @Test
    void decode_quandProprieteInconnue_alorsUnreadableCacheValueException() {
        assertUnreadable(array(validElement().replace("{", "{\"extra\":1,")));
    }

    @Test
    void decode_quandProprieteManquante_alorsUnreadableCacheValueException() {
        assertUnreadable(array("{\"id\":\"" + VALID_ID + "\",\"isbn\":\"" + VALID_ISBN
                + "\",\"title\":\"T\",\"author\":\"A\",\"availableCopies\":1}"));
    }

    @Test
    void decode_quandChampNull_alorsUnreadableCacheValueException() {
        assertUnreadable(array(element("\"" + VALID_ID + "\"", "null", "\"T\"", "\"A\"", "3", "2")));
    }

    @Test
    void decode_quandNombreNull_alorsUnreadableCacheValueException() {
        assertUnreadable(array(element("\"" + VALID_ID + "\"", "\"" + VALID_ISBN + "\"", "\"T\"", "\"A\"", "null", "2")));
    }

    @Test
    void decode_quandUuidInvalide_alorsUnreadableCacheValueException() {
        assertUnreadable(array(element("\"pas-un-uuid\"", "\"" + VALID_ISBN + "\"", "\"T\"", "\"A\"", "3", "2")));
    }

    @Test
    void decode_quandIsbnInvalide_alorsUnreadableCacheValueException() {
        assertUnreadable(array(element("\"" + VALID_ID + "\"", "\"123\"", "\"T\"", "\"A\"", "3", "2")));
    }

    @Test
    void decode_quandTitreNull_alorsUnreadableCacheValueException() {
        assertUnreadable(array(element("\"" + VALID_ID + "\"", "\"" + VALID_ISBN + "\"", "null", "\"A\"", "3", "2")));
    }

    @Test
    void decode_quandTitreVide_alorsUnreadableCacheValueException() {
        assertUnreadable(array(element("\"" + VALID_ID + "\"", "\"" + VALID_ISBN + "\"", "\"  \"", "\"A\"", "3", "2")));
    }

    @Test
    void decode_quandDisponiblesSuperieursAuTotal_alorsUnreadableCacheValueException() {
        assertUnreadable(array(element("\"" + VALID_ID + "\"", "\"" + VALID_ISBN + "\"", "\"T\"", "\"A\"", "2", "3")));
    }

    @Test
    void decode_quandExemplairesNegatifs_alorsUnreadableCacheValueException() {
        assertUnreadable(array(element("\"" + VALID_ID + "\"", "\"" + VALID_ISBN + "\"", "\"T\"", "\"A\"", "3", "-1")));
        assertUnreadable(array(element("\"" + VALID_ID + "\"", "\"" + VALID_ISBN + "\"", "\"T\"", "\"A\"", "-3", "-3")));
    }

    @Test
    void decode_quandLivreSansExemplaireDisponible_alorsUnreadableCacheValueException() {
        assertUnreadable(array(element("\"" + VALID_ID + "\"", "\"" + VALID_ISBN + "\"", "\"T\"", "\"A\"", "2", "0")));
    }

    @Test
    void decode_quandPlusDeVingtElements_alorsUnreadableCacheValueException() {
        // Given
        List<String> elements = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            elements.add(element("\"" + UUID.randomUUID() + "\"", "\"978000000%04d\"".formatted(i), "\"Livre\"",
                    "\"Auteur\"", "1", "1"));
        }
        // When / Then
        assertUnreadable(array(elements.toArray(String[]::new)));
    }

    @Test
    void decode_quandIsbnEnDouble_alorsUnreadableCacheValueException() {
        // Given : deux livres distincts avec le même ISBN
        String first = element("\"" + UUID.randomUUID() + "\"", "\"" + VALID_ISBN + "\"", "\"A\"", "\"X\"", "1", "1");
        String second = element("\"" + UUID.randomUUID() + "\"", "\"" + VALID_ISBN + "\"", "\"B\"", "\"Y\"", "1", "1");
        // When / Then
        assertUnreadable(array(first, second));
    }

    @Test
    void decode_quandJsonTronqueOuAvecResidu_alorsUnreadableCacheValueException() {
        assertUnreadable(array(validElement()) + " residu");
        assertUnreadable("[" + validElement());
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    private static String elementWith(String title, String author, int total, int available) {
        return element(quoted(VALID_ID), quoted(VALID_ISBN), quoted(title), quoted(author),
                String.valueOf(total), String.valueOf(available));
    }

    @Test
    void decode_quandTotalAuDessusDeLaBorneHaute_alorsUnreadableCacheValueExceptionExemplairesIncoherents() {
        // Given : Book.restore ne contrôle rien, le codec doit refuser MAX_COPIES + 1
        int total = Book.MAX_COPIES + 1;
        String value = array(elementWith("T", "A", total, 1));
        // When / Then
        assertThatThrownBy(() -> codec.decode(value))
                .isInstanceOf(UnreadableCacheValueException.class)
                .hasMessage(AvailableBooksCacheCodec.REASON_INCONSISTENT_COPIES);
    }

    @Test
    void decode_quandTotalEgalALaBorneHaute_alorsAccepte() {
        // Given
        String value = array(elementWith("T", "A", Book.MAX_COPIES, Book.MAX_COPIES));
        // When
        List<Book> decoded = codec.decode(value);
        // Then
        assertThat(decoded).singleElement().satisfies(book -> {
            assertThat(book.getTotalCopies()).isEqualTo(Book.MAX_COPIES);
            assertThat(book.getAvailableCopies()).isEqualTo(Book.MAX_COPIES);
        });
    }

    @Test
    void decode_quandTitreTropLong_alorsUnreadableCacheValueExceptionTexteTropLong() {
        // Given
        String value = array(elementWith("x".repeat(Book.TEXT_MAX_LENGTH + 1), "A", 3, 2));
        // When / Then
        assertThatThrownBy(() -> codec.decode(value))
                .isInstanceOf(UnreadableCacheValueException.class)
                .hasMessage(AvailableBooksCacheCodec.REASON_TEXT_TOO_LONG);
    }

    @Test
    void decode_quandAuteurTropLong_alorsUnreadableCacheValueExceptionTexteTropLong() {
        // Given
        String value = array(elementWith("T", "x".repeat(Book.TEXT_MAX_LENGTH + 1), 3, 2));
        // When / Then
        assertThatThrownBy(() -> codec.decode(value))
                .isInstanceOf(UnreadableCacheValueException.class)
                .hasMessage(AvailableBooksCacheCodec.REASON_TEXT_TOO_LONG);
    }

    @Test
    void decode_quandTitreEtAuteurALaLongueurMaximale_alorsAccepte() {
        // Given
        String maxText = "x".repeat(Book.TEXT_MAX_LENGTH);
        String value = array(elementWith(maxText, maxText, 3, 2));
        // When
        List<Book> decoded = codec.decode(value);
        // Then
        assertThat(decoded).singleElement().satisfies(book -> {
            assertThat(book.getTitle()).hasSize(Book.TEXT_MAX_LENGTH);
            assertThat(book.getAuthor()).hasSize(Book.TEXT_MAX_LENGTH);
        });
    }

    @Test
    void decode_quandTitreALaLongueurMaximaleEntoureDEspaces_alorsAccepteCarSeuleLaLongueurApresStripCompte() {
        // Given : TEXT_MAX_LENGTH caractères utiles, plus des espaces autour
        String padded = "  " + "x".repeat(Book.TEXT_MAX_LENGTH) + "  ";
        String value = array(elementWith(padded, "A", 3, 2));
        // When
        List<Book> decoded = codec.decode(value);
        // Then
        assertThat(decoded).hasSize(1);
    }

    @Test
    void decode_quandIllisible_alorsMessageDExceptionSansLaValeur() {
        // Given : des valeurs qui contiennent un marqueur reconnaissable (RC-18)
        String marker = "MARQUEUR-SECRET-123";
        String notJson = "pas du json " + marker;
        String incoherent = array(element("\"" + VALID_ID + "\"", "\"INVALIDE-" + marker + "\"", "\"" + marker + "\"",
                "\"A\"", "3", "2"));
        String unknown = array(validElement().replace("{", "{\"" + marker + "\":1,"));
        // When / Then
        for (String value : List.of(notJson, incoherent, unknown)) {
            assertThatThrownBy(() -> codec.decode(value))
                    .isInstanceOf(UnreadableCacheValueException.class)
                    .hasMessageNotContaining(marker);
        }
    }
}
