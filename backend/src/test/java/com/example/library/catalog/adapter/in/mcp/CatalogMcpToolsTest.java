package com.example.library.catalog.adapter.in.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.library.catalog.application.port.in.GetBookByIsbnQuery;
import com.example.library.catalog.application.port.in.GetBookByIsbnUseCase;
import com.example.library.catalog.application.port.in.ListAvailableBooksQuery;
import com.example.library.catalog.application.port.in.ListAvailableBooksUseCase;
import com.example.library.catalog.domain.exception.BookNotFoundException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Adapter MCP : outils get_book et list_available_books, ports d'entree mockes (RM-MCP-03 a RM-MCP-09). */
@ExtendWith(OutputCaptureExtension.class)
class CatalogMcpToolsTest {

    private static final String ISBN = "9782070368228";
    private static final String INVALID_ISBN_MESSAGE =
            "ISBN invalide : il doit comporter 13 chiffres (tirets et espaces tolérés), par exemple 9782070368228.";
    private static final String INVALID_LIMIT_MESSAGE =
            "limit doit être un entier compris entre 1 et 20 (10 par défaut si absent).";

    private final GetBookByIsbnUseCase getBookByIsbnUseCase = mock(GetBookByIsbnUseCase.class);
    private final ListAvailableBooksUseCase listAvailableBooksUseCase = mock(ListAvailableBooksUseCase.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final CatalogMcpTools tools =
            new CatalogMcpTools(getBookByIsbnUseCase, listAvailableBooksUseCase, jsonMapper);

    private static Book book(String isbn, String title, int total, int available) {
        return Book.restore(BookId.newId(), Isbn.of(isbn), title, "Albert Camus", total, available);
    }

    private static String text(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((TextContent) result.content().get(0)).text();
    }

    private static void assertNoTechnicalLeak(String message) {
        assertThat(message).doesNotContain("Exception", "com.example", "\tat ", "java.", "org.springframework");
    }

    // ----- get_book -----

    @Test
    void getBook_quandLivreExistant_alorsFicheSansIdentifiantTechnique() {
        // Given
        when(getBookByIsbnUseCase.getByIsbn(any())).thenReturn(book(ISBN, "L'Étranger", 3, 2));
        // When
        CallToolResult result = tools.getBook(ISBN);
        // Then
        assertThat(result.isError()).isFalse();
        JsonNode json = jsonMapper.readTree(text(result));
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "isbn", "title", "author", "availableCopies", "totalCopies");
        assertThat(json.get("isbn").asString()).isEqualTo(ISBN);
        assertThat(json.get("title").asString()).isEqualTo("L'Étranger");
        assertThat(json.get("author").asString()).isEqualTo("Albert Camus");
        assertThat(json.get("availableCopies").asInt()).isEqualTo(2);
        assertThat(json.get("totalCopies").asInt()).isEqualTo(3);
        assertThat(json.has("id")).isFalse();
    }

    @Test
    void getBook_quandIsbnFormate_alorsIsbnNormaliseTransmisAuPort() {
        // Given
        when(getBookByIsbnUseCase.getByIsbn(any())).thenReturn(book(ISBN, "L'Étranger", 3, 2));
        // When
        CallToolResult result = tools.getBook("978-2-07-036822-8");
        // Then
        ArgumentCaptor<GetBookByIsbnQuery> captor = ArgumentCaptor.forClass(GetBookByIsbnQuery.class);
        verify(getBookByIsbnUseCase).getByIsbn(captor.capture());
        assertThat(captor.getValue().isbn().value()).isEqualTo(ISBN);
        assertThat(result.isError()).isFalse();
        assertThat(jsonMapper.readTree(text(result)).get("isbn").asString()).isEqualTo(ISBN);
    }

    @Test
    void getBook_quandAucunExemplaireDisponible_alorsSuccesAvecZeroDisponible() {
        // Given
        when(getBookByIsbnUseCase.getByIsbn(any())).thenReturn(book(ISBN, "T", 2, 0));
        // When
        CallToolResult result = tools.getBook(ISBN);
        // Then
        assertThat(result.isError()).isFalse();
        JsonNode json = jsonMapper.readTree(text(result));
        assertThat(json.get("availableCopies").asInt()).isZero();
        assertThat(json.get("totalCopies").asInt()).isEqualTo(2);
    }

    @Test
    void getBook_quandLivreAbsent_alorsErreurAvecMessageFrancaisSansDetailTechnique() {
        // Given
        Isbn unknown = Isbn.of("9780000000002");
        when(getBookByIsbnUseCase.getByIsbn(any())).thenThrow(new BookNotFoundException(unknown));
        // When
        CallToolResult result = tools.getBook("9780000000002");
        // Then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("Aucun livre trouvé pour l'ISBN 9780000000002.");
        assertNoTechnicalLeak(text(result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"978207036822", "97820703682280", "978207036822A", "   ", "123"})
    @NullAndEmptySource
    void getBook_quandIsbnInvalide_alorsErreurSansInterrogerLeCatalogue(String isbn) {
        // When
        CallToolResult result = tools.getBook(isbn);
        // Then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo(INVALID_ISBN_MESSAGE).contains("13 chiffres");
        assertNoTechnicalLeak(text(result));
        verifyNoInteractions(getBookByIsbnUseCase);
    }

    @Test
    void getBook_quandErreurImprevue_alorsMessageGeneriqueEtJournalSansArgument(CapturedOutput output) {
        // Given
        when(getBookByIsbnUseCase.getByIsbn(any())).thenThrow(new IllegalStateException("jdbc:postgresql://secret-host"));
        // When
        CallToolResult result = tools.getBook(ISBN);
        // Then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("Erreur interne, réessayez plus tard.");
        assertNoTechnicalLeak(text(result));
        assertThat(text(result)).doesNotContain("secret-host");
        assertThat(output.getAll()).contains("Erreur imprévue dans l'outil MCP get_book");
    }

    // ----- list_available_books -----

    @Test
    void listAvailableBooks_quandLimiteAbsente_alorsDixTransmisAuPort() {
        // Given
        when(listAvailableBooksUseCase.listAvailable(any())).thenReturn(List.of());
        // When
        CallToolResult result = tools.listAvailableBooks(null);
        // Then
        assertThat(result.isError()).isFalse();
        assertThat(capturedLimit()).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 20})
    void listAvailableBooks_quandLimiteValide_alorsTransmiseTelleQuelle(int limit) {
        // Given
        when(listAvailableBooksUseCase.listAvailable(any())).thenReturn(List.of());
        // When
        CallToolResult result = tools.listAvailableBooks(limit);
        // Then
        assertThat(result.isError()).isFalse();
        assertThat(capturedLimit()).isEqualTo(limit);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 21, 1000})
    void listAvailableBooks_quandLimiteHorsBornes_alorsErreurSansLectureDuCatalogue(int limit) {
        // When
        CallToolResult result = tools.listAvailableBooks(limit);
        // Then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo(INVALID_LIMIT_MESSAGE).contains("entre 1 et 20");
        assertNoTechnicalLeak(text(result));
        verifyNoInteractions(listAvailableBooksUseCase);
    }

    @Test
    void listAvailableBooks_quandLivresDisponibles_alorsListeDeFichesDansLOrdreDuPort() {
        // Given
        when(listAvailableBooksUseCase.listAvailable(any())).thenReturn(List.of(
                book("9782070368228", "Alpha", 3, 2), book("9782070368235", "Beta", 1, 1)));
        // When
        CallToolResult result = tools.listAvailableBooks(10);
        // Then
        assertThat(result.isError()).isFalse();
        JsonNode json = jsonMapper.readTree(text(result));
        assertThat(json.isArray()).isTrue();
        assertThat(json).hasSize(2);
        assertThat(json.get(0).get("title").asString()).isEqualTo("Alpha");
        assertThat(json.get(0).get("availableCopies").asInt()).isEqualTo(2);
        assertThat(json.get(0).get("totalCopies").asInt()).isEqualTo(3);
        assertThat(json.get(1).get("isbn").asString()).isEqualTo("9782070368235");
        assertThat(json.get(0).has("id")).isFalse();
        assertThat(json.get(1).has("id")).isFalse();
    }

    @Test
    void listAvailableBooks_quandAucunLivre_alorsSuccesAvecListeVide() {
        // Given
        when(listAvailableBooksUseCase.listAvailable(any())).thenReturn(List.of());
        // When
        CallToolResult result = tools.listAvailableBooks(10);
        // Then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).isEqualTo("[]");
    }

    @Test
    void listAvailableBooks_quandErreurImprevue_alorsMessageGeneriqueEtJournal(CapturedOutput output) {
        // Given
        when(listAvailableBooksUseCase.listAvailable(any())).thenThrow(new IllegalStateException("boom SQL"));
        // When
        CallToolResult result = tools.listAvailableBooks(5);
        // Then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("Erreur interne, réessayez plus tard.");
        assertThat(text(result)).doesNotContain("boom");
        assertThat(output.getAll()).contains("Erreur imprévue dans l'outil MCP list_available_books");
    }

    private int capturedLimit() {
        ArgumentCaptor<ListAvailableBooksQuery> captor = ArgumentCaptor.forClass(ListAvailableBooksQuery.class);
        verify(listAvailableBooksUseCase).listAvailable(captor.capture());
        return captor.getValue().limit().value();
    }
}
