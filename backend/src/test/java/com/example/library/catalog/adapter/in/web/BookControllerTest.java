package com.example.library.catalog.adapter.in.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.library.catalog.application.port.in.RegisterBookCommand;
import com.example.library.catalog.application.port.in.RegisterBookUseCase;
import com.example.library.catalog.domain.exception.IsbnAlreadyExistsException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.Isbn;
import com.example.library.config.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Adapter web : contrat HTTP de POST /api/v1/books (RM-CAT-01 a RM-CAT-10). */
@WebMvcTest(BookController.class)
@Import(SecurityConfig.class)
class BookControllerTest {

    private static final String URL = "/api/v1/books";
    private static final String VALID_ISBN = "9782070368228";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegisterBookUseCase registerBookUseCase;

    private static RequestPostProcessor librarian() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_LIBRARIAN"));
    }

    private static RequestPostProcessor member() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_MEMBER"));
    }

    private static String json(String isbn, String title, String author, String copies) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (isbn != null) {
            sb.append("\"isbn\":\"").append(isbn).append('"');
            first = false;
        }
        if (title != null) {
            sb.append(first ? "" : ",").append("\"title\":\"").append(title).append('"');
            first = false;
        }
        if (author != null) {
            sb.append(first ? "" : ",").append("\"author\":\"").append(author).append('"');
            first = false;
        }
        if (copies != null) {
            sb.append(first ? "" : ",").append("\"copies\":").append(copies);
        }
        return sb.append('}').toString();
    }

    private static String validBody() {
        return json(VALID_ISBN, "L'Étranger", "Albert Camus", "3");
    }

    private ResultActions postAs(RequestPostProcessor user, String body) throws Exception {
        return mockMvc.perform(post(URL).with(user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions assertValidationError(ResultActions result, String field, String code) throws Exception {
        return result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='" + field + "' && @.code=='" + code + "')]", hasSize(1)));
    }

    // ---- Succes

    @Test
    void register_quandLibrarianEtCorpsValide_alors201LocationEtCorps() throws Exception {
        // Given
        Book book = Book.register(Isbn.of(VALID_ISBN), "L'Étranger", "Albert Camus", 3);
        when(registerBookUseCase.register(any())).thenReturn(book);
        // When
        ResultActions result = postAs(librarian(), validBody());
        // Then
        String id = book.getId().value().toString();
        result.andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/books/" + id)))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.isbn").value(VALID_ISBN))
                .andExpect(jsonPath("$.title").value("L'Étranger"))
                .andExpect(jsonPath("$.author").value("Albert Camus"))
                .andExpect(jsonPath("$.totalCopies").value(3))
                .andExpect(jsonPath("$.availableCopies").value(3));
        ArgumentCaptor<RegisterBookCommand> captor = ArgumentCaptor.forClass(RegisterBookCommand.class);
        verify(registerBookUseCase).register(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().copies()).isEqualTo(3);
    }

    @Test
    void register_quandIsbnAvecTirets_alorsCommandeAvecIsbnNormalise() throws Exception {
        // Given
        when(registerBookUseCase.register(any()))
                .thenReturn(Book.register(Isbn.of(VALID_ISBN), "T", "A", 1));
        // When
        postAs(librarian(), json("978-2-07-036822-8", "T", "A", "1")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.isbn").value(VALID_ISBN));
        // Then
        ArgumentCaptor<RegisterBookCommand> captor = ArgumentCaptor.forClass(RegisterBookCommand.class);
        verify(registerBookUseCase).register(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().isbn().value()).isEqualTo(VALID_ISBN);
    }

    @Test
    void register_quandIsbnAvecEspacesAutour_alors201() throws Exception {
        // Given
        when(registerBookUseCase.register(any()))
                .thenReturn(Book.register(Isbn.of(VALID_ISBN), "T", "A", 1));
        // When / Then
        postAs(librarian(), json(" 9782070368228 ", "T", "A", "1")).andExpect(status().isCreated());
        ArgumentCaptor<RegisterBookCommand> captor = ArgumentCaptor.forClass(RegisterBookCommand.class);
        verify(registerBookUseCase).register(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().isbn().value()).isEqualTo(VALID_ISBN);
    }

    @Test
    void register_quandTitreDe255Caracteres_alors201() throws Exception {
        // Given
        when(registerBookUseCase.register(any()))
                .thenReturn(Book.register(Isbn.of(VALID_ISBN), "a".repeat(255), "A", 1));
        // When / Then
        postAs(librarian(), json(VALID_ISBN, "a".repeat(255), "A", "1")).andExpect(status().isCreated());
    }

    @Test
    void register_quandCopiesAuxBornes_alors201() throws Exception {
        // Given
        when(registerBookUseCase.register(any()))
                .thenReturn(Book.register(Isbn.of(VALID_ISBN), "T", "A", 1000));
        // When / Then
        postAs(librarian(), json(VALID_ISBN, "T", "A", "1")).andExpect(status().isCreated());
        postAs(librarian(), json(VALID_ISBN, "T", "A", "1000")).andExpect(status().isCreated());
    }

    @Test
    void register_quandCopiesEnChaineNumerique_alors201() throws Exception {
        // Given : "3" (chaine) est accepte (decision du plan)
        when(registerBookUseCase.register(any()))
                .thenReturn(Book.register(Isbn.of(VALID_ISBN), "T", "A", 3));
        // When / Then
        postAs(librarian(), json(VALID_ISBN, "T", "A", "\"3\"")).andExpect(status().isCreated());
    }

    @Test
    void register_quandTokenMemberEtLibrarian_alorsAutorise() throws Exception {
        // Given
        when(registerBookUseCase.register(any()))
                .thenReturn(Book.register(Isbn.of(VALID_ISBN), "T", "A", 1));
        RequestPostProcessor both = jwt().authorities(
                new SimpleGrantedAuthority("ROLE_MEMBER"), new SimpleGrantedAuthority("ROLE_LIBRARIAN"));
        // When / Then
        postAs(both, validBody()).andExpect(status().isCreated());
    }

    // ---- Securite

    @Test
    void register_quandSansToken_alors401EtUseCaseJamaisAppele() throws Exception {
        // When / Then
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandTokenInvalide_alors401() throws Exception {
        // When / Then
        mockMvc.perform(post(URL).header("Authorization", "Bearer not.a.valid-token")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandRoleMember_alors403EtUseCaseJamaisAppele() throws Exception {
        // When / Then
        postAs(member(), validBody()).andExpect(status().isForbidden());
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandMemberEtCorpsInvalide_alors403Pas400() throws Exception {
        // Given : H10, l'autorisation passe avant la validation
        String invalid = json("123", "", "   ", "0");
        // When / Then
        postAs(member(), invalid).andExpect(status().isForbidden());
        verify(registerBookUseCase, never()).register(any());
    }

    @Test
    void register_quandMemberEtJsonMalforme_alors403() throws Exception {
        // When / Then
        postAs(member(), "{pas du json").andExpect(status().isForbidden());
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandAuthentifieSansRole_alors403() throws Exception {
        // When / Then
        postAs(jwt().authorities(), validBody()).andExpect(status().isForbidden());
        verifyNoInteractions(registerBookUseCase);
    }

    // ---- 400 ISBN

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "978207036822", "97820703682280", "978207036822A", "978.2070368228", "", "   "})
    void register_quandIsbnInvalide_alors400EtUseCaseJamaisAppele(String isbn) throws Exception {
        // When
        ResultActions result = postAs(librarian(), json(isbn, "T", "A", "1"));
        // Then
        assertValidationError(result, "isbn", "INVALID_FORMAT");
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandIsbnAbsent_alors400Required() throws Exception {
        // When
        ResultActions result = postAs(librarian(), json(null, "T", "A", "1"));
        // Then
        assertValidationError(result, "isbn", "REQUIRED");
        verifyNoInteractions(registerBookUseCase);
    }

    // ---- 400 titre / auteur

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "   "})
    void register_quandTitreVideOuBlanc_alors400Blank(String title) throws Exception {
        // When
        ResultActions result = postAs(librarian(), json(VALID_ISBN, title, "A", "1"));
        // Then
        assertValidationError(result, "title", "BLANK");
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandTitreAbsent_alors400Required() throws Exception {
        assertValidationError(postAs(librarian(), json(VALID_ISBN, null, "A", "1")), "title", "REQUIRED");
        verifyNoInteractions(registerBookUseCase);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "   "})
    void register_quandAuteurVideOuBlanc_alors400Blank(String author) throws Exception {
        // When
        ResultActions result = postAs(librarian(), json(VALID_ISBN, "T", author, "1"));
        // Then
        assertValidationError(result, "author", "BLANK");
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandAuteurAbsent_alors400Required() throws Exception {
        assertValidationError(postAs(librarian(), json(VALID_ISBN, "T", null, "1")), "author", "REQUIRED");
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandTitreDe256Caracteres_alors400TooLong() throws Exception {
        // When
        ResultActions result = postAs(librarian(), json(VALID_ISBN, "a".repeat(256), "A", "1"));
        // Then
        assertValidationError(result, "title", "TOO_LONG");
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandAuteurDe256Caracteres_alors400TooLong() throws Exception {
        // When
        ResultActions result = postAs(librarian(), json(VALID_ISBN, "T", "a".repeat(256), "1"));
        // Then
        assertValidationError(result, "author", "TOO_LONG");
        verifyNoInteractions(registerBookUseCase);
    }

    // ---- 400 copies

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "-1", "1001"})
    void register_quandCopiesHorsBornes_alors400OutOfRange(String copies) throws Exception {
        // When
        ResultActions result = postAs(librarian(), json(VALID_ISBN, "T", "A", copies));
        // Then
        assertValidationError(result, "copies", "OUT_OF_RANGE");
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandCopiesDecimal_alors400InvalidFormat() throws Exception {
        // When : 2.5 ne doit pas etre tronque en 2
        ResultActions result = postAs(librarian(), json(VALID_ISBN, "T", "A", "2.5"));
        // Then
        assertValidationError(result, "copies", "INVALID_FORMAT");
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandCopiesTexteNonNumerique_alors400InvalidFormat() throws Exception {
        // When
        ResultActions result = postAs(librarian(), json(VALID_ISBN, "T", "A", "\"trois\""));
        // Then
        assertValidationError(result, "copies", "INVALID_FORMAT");
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandCopiesAbsent_alors400Required() throws Exception {
        // When
        ResultActions result = postAs(librarian(), json(VALID_ISBN, "T", "A", null));
        // Then
        assertValidationError(result, "copies", "REQUIRED");
        verifyNoInteractions(registerBookUseCase);
    }

    // ---- 400 global

    @Test
    void register_quandQuatreErreurs_alorsToutesRenvoyeesEnsemble() throws Exception {
        // Given
        String body = json("123", "", "   ", "0");
        // When
        ResultActions result = postAs(librarian(), body);
        // Then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("isbn", "title", "author", "copies")));
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandCorpsVide_alorsQuatreErreursRequired() throws Exception {
        // When / Then
        postAs(librarian(), "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors[*].code", containsInAnyOrder("REQUIRED", "REQUIRED", "REQUIRED", "REQUIRED")));
    }

    @Test
    void register_quandJsonMalforme_alors400ProblemDetail() throws Exception {
        // When
        ResultActions result = postAs(librarian(), "{\"isbn\": ");
        // Then
        result.andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(0)));
        verifyNoInteractions(registerBookUseCase);
    }

    @Test
    void register_quandErreurDeValidation_alorsFormatProblemDetailComplet() throws Exception {
        // When / Then
        postAs(librarian(), json(VALID_ISBN, "", "A", "1")).andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.errors[0].field").value("title"))
                .andExpect(jsonPath("$.errors[0].message").exists());
    }

    // ---- 409

    @Test
    void register_quandIsbnDejaExistant_alors409IsbnAlreadyExists() throws Exception {
        // Given
        when(registerBookUseCase.register(any()))
                .thenThrow(new IsbnAlreadyExistsException(Isbn.of(VALID_ISBN)));
        // When / Then
        postAs(librarian(), validBody()).andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("ISBN_ALREADY_EXISTS"));
    }

    @Test
    void register_quandIsbnExistantSousAutreForme_alorsCommandeNormaliseePuis409() throws Exception {
        // Given
        when(registerBookUseCase.register(any()))
                .thenThrow(new IsbnAlreadyExistsException(Isbn.of(VALID_ISBN)));
        // When
        postAs(librarian(), json("978-2-07-036822-8", "T", "A", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ISBN_ALREADY_EXISTS")));
        // Then
        ArgumentCaptor<RegisterBookCommand> captor = ArgumentCaptor.forClass(RegisterBookCommand.class);
        verify(registerBookUseCase).register(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().isbn().value()).isEqualTo(VALID_ISBN);
    }

    @Test
    void register_quandErreurInconnueDetailleSql_alorsDetailSansSql() throws Exception {
        // Given : le detail 409 ne doit citer ni SQL ni nom de contrainte
        when(registerBookUseCase.register(any()))
                .thenThrow(new IsbnAlreadyExistsException(Isbn.of(VALID_ISBN)));
        // When / Then
        String content = postAs(librarian(), validBody()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(content).doesNotContain("uk_book_isbn").doesNotContainIgnoringCase("sql");
    }
}
