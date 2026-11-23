package com.example.library.catalog.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.example.library.catalog.application.port.in.RegisterBookUseCase;
import com.example.library.catalog.application.port.in.SearchAvailableBooksQuery;
import com.example.library.catalog.application.port.in.SearchAvailableBooksUseCase;
import com.example.library.catalog.domain.model.AvailableBooksPage;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.CatalogPageRequest;
import com.example.library.catalog.domain.model.Isbn;
import com.example.library.config.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Adapter web : contrat HTTP de GET /api/v1/books (RM-CAT-13, 15, 17, 18, 19, 20, 21, 23). */
@WebMvcTest(BookController.class)
@Import(SecurityConfig.class)
class BookControllerSearchTest {

    private static final String URL = "/api/v1/books";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SearchAvailableBooksUseCase searchAvailableBooksUseCase;

    @MockitoBean
    private RegisterBookUseCase registerBookUseCase;

    private static RequestPostProcessor librarian() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_LIBRARIAN"));
    }

    private static RequestPostProcessor member() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_MEMBER"));
    }

    private static Book book(String isbn, String title, String author, int copies) {
        return Book.register(Isbn.of(isbn), title, author, copies);
    }

    private void givenPage(List<Book> content, int page, int size, long total) {
        when(searchAvailableBooksUseCase.search(any()))
                .thenReturn(new AvailableBooksPage(content, new CatalogPageRequest(page, size), total));
    }

    private SearchAvailableBooksQuery capturedQuery() {
        ArgumentCaptor<SearchAvailableBooksQuery> captor = ArgumentCaptor.forClass(SearchAvailableBooksQuery.class);
        verify(searchAvailableBooksUseCase).search(captor.capture());
        return captor.getValue();
    }

    private ResultActions assertValidationError(ResultActions result, String field, String code) throws Exception {
        return result.andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='" + field + "' && @.code=='" + code + "')]", hasSize(1)));
    }

    // ---- Succès et contrat de réponse (RM-CAT-20)

    @Test
    void search_quandMemberSansParametre_alors200EnveloppePagineeEtDefauts() throws Exception {
        // Given
        Book book = book("9782070368228", "L'Étranger", "Albert Camus", 3);
        givenPage(List.of(book), 0, 10, 25);
        // When
        ResultActions result = mockMvc.perform(get(URL).with(member()));
        // Then
        String id = book.getId().value().toString();
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].isbn").value("9782070368228"))
                .andExpect(jsonPath("$.content[0].title").value("L'Étranger"))
                .andExpect(jsonPath("$.content[0].author").value("Albert Camus"))
                .andExpect(jsonPath("$.content[0].totalCopies").value(3))
                .andExpect(jsonPath("$.content[0].availableCopies").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3));
        SearchAvailableBooksQuery query = capturedQuery();
        assertThat(query.search().pageRequest()).isEqualTo(new CatalogPageRequest(0, 10));
        assertThat(query.search().text().isEmpty()).isTrue();
    }

    @Test
    void search_quandReponse_alorsSeulsLesChampsDuContratSontExposes() throws Exception {
        // Given
        givenPage(List.of(book("9782070368228", "T", "A", 1)), 0, 10, 1);
        // When / Then : aucune entité ni objet de domaine exposé
        mockMvc.perform(get(URL).with(librarian()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.content[0].*", hasSize(6)));
    }

    @Test
    void search_quandLibrarian_alors200() throws Exception {
        // Given
        givenPage(List.of(), 0, 10, 0);
        // When / Then
        mockMvc.perform(get(URL).with(librarian())).andExpect(status().isOk());
    }

    @Test
    void search_quandAucunResultat_alors200ContenuVideEtTotauxZero() throws Exception {
        // Given
        givenPage(List.of(), 0, 10, 0);
        // When / Then
        mockMvc.perform(get(URL).with(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void search_quandPageAuDelaDeLaDerniere_alors200ContenuVideEtTotauxCorrects() throws Exception {
        // Given
        givenPage(List.of(), 9, 10, 25);
        // When / Then
        mockMvc.perform(get(URL).param("page", "9").with(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page").value(9))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    // ---- Paramètres transmis au port d'entrée

    @Test
    void search_quandPageSizeEtQ_alorsQueryConstruiteAvecCesValeurs() throws Exception {
        // Given
        givenPage(List.of(), 1, 20, 0);
        // When
        mockMvc.perform(get(URL).param("page", "1").param("size", "20").param("q", "camus").with(member()))
                .andExpect(status().isOk());
        // Then
        SearchAvailableBooksQuery query = capturedQuery();
        assertThat(query.search().pageRequest()).isEqualTo(new CatalogPageRequest(1, 20));
        assertThat(query.search().text().value()).isEqualTo("camus");
    }

    @Test
    void search_quandQAvecEspaces_alorsTransmisRogne() throws Exception {
        // Given
        givenPage(List.of(), 0, 10, 0);
        // When
        mockMvc.perform(get(URL).param("q", "  zola ").with(member())).andExpect(status().isOk());
        // Then
        assertThat(capturedQuery().search().text().value()).isEqualTo("zola");
    }

    @Test
    void search_quandQBlanc_alorsAucunFiltre() throws Exception {
        // Given
        givenPage(List.of(), 0, 10, 0);
        // When
        mockMvc.perform(get(URL).param("q", "   ").with(member())).andExpect(status().isOk());
        // Then
        assertThat(capturedQuery().search().text().isEmpty()).isTrue();
    }

    @Test
    void search_quandPageEtSizeVides_alorsValeursParDefaut() throws Exception {
        // Given
        givenPage(List.of(), 0, 10, 0);
        // When
        mockMvc.perform(get(URL + "?page=&size=&q=").with(member())).andExpect(status().isOk());
        // Then
        assertThat(capturedQuery().search().pageRequest()).isEqualTo(new CatalogPageRequest(0, 10));
    }

    @Test
    void search_quandQAvecCaracteresSpeciaux_alorsTransmisTelQuel() throws Exception {
        // Given
        givenPage(List.of(), 0, 10, 0);
        // When
        mockMvc.perform(get(URL).param("q", "100%_\\").with(member())).andExpect(status().isOk());
        // Then : l'échappement LIKE est l'affaire de l'adapter de persistance
        assertThat(capturedQuery().search().text().value()).isEqualTo("100%_\\");
    }

    @Test
    void search_quandQDeCentCaracteres_alors200() throws Exception {
        // Given
        givenPage(List.of(), 0, 10, 0);
        // When / Then
        mockMvc.perform(get(URL).param("q", "a".repeat(100)).with(member())).andExpect(status().isOk());
    }

    @Test
    void search_quandQDeCentCaracteresEntreEspaces_alors200() throws Exception {
        // Given : la longueur est mesurée après trim
        givenPage(List.of(), 0, 10, 0);
        // When / Then
        mockMvc.perform(get(URL).param("q", "  " + "a".repeat(100) + "  ").with(member()))
                .andExpect(status().isOk());
    }

    @Test
    void search_quandTailleAuxBornes_alors200() throws Exception {
        // Given
        givenPage(List.of(), 0, 50, 0);
        // When / Then
        mockMvc.perform(get(URL).param("size", "1").with(member())).andExpect(status().isOk());
        mockMvc.perform(get(URL).param("size", "50").with(member())).andExpect(status().isOk());
    }

    @Test
    void search_quandParametreSortInconnu_alorsIgnoreEtOrdreInchange() throws Exception {
        // Given : RM-CAT-21
        givenPage(List.of(), 0, 10, 0);
        // When
        mockMvc.perform(get(URL).param("sort", "author,desc").param("foo", "bar").with(member()))
                .andExpect(status().isOk());
        // Then : la requête transmise ne porte que page, size, q
        assertThat(capturedQuery().search().pageRequest()).isEqualTo(new CatalogPageRequest(0, 10));
    }

    // ---- Sécurité (RM-CAT-13)

    @Test
    void search_quandSansToken_alors401ProblemDetailEtUseCaseJamaisAppele() throws Exception {
        // Given / When / Then
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(searchAvailableBooksUseCase);
    }

    @Test
    void search_quandJetonInvalide_alors401() throws Exception {
        // Given / When / Then
        mockMvc.perform(get(URL).header("Authorization", "Bearer pas-un-jwt"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(searchAvailableBooksUseCase);
    }

    @Test
    void search_quandAuthentifieSansRoleLibrarianNiMember_alors403AccessDenied() throws Exception {
        // Given / When / Then
        mockMvc.perform(get(URL).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OTHER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verifyNoInteractions(searchAvailableBooksUseCase);
    }

    @Test
    void search_quandJetonSansAucuneAutorite_alors403() throws Exception {
        // Given / When / Then
        mockMvc.perform(get(URL).with(jwt().authorities()))
                .andExpect(status().isForbidden());
    }

    @Test
    void search_quandRequeteGetAuthentifieeSansCsrf_alorsPasDeRejetCsrf() throws Exception {
        // Given
        givenPage(List.of(), 0, 10, 0);
        // When / Then : pas d'en-tête X-XSRF-TOKEN, la lecture passe
        mockMvc.perform(get(URL).with(member())).andExpect(status().isOk());
    }

    // ---- 400 : paramètres invalides (RM-CAT-15, RM-CAT-17)

    @ParameterizedTest
    @CsvSource({
            "page,-1,page,OUT_OF_RANGE",
            "size,0,size,OUT_OF_RANGE",
            "size,51,size,OUT_OF_RANGE",
            "size,-5,size,OUT_OF_RANGE",
            "page,abc,page,INVALID_FORMAT",
            "size,abc,size,INVALID_FORMAT",
            "page,1.5,page,INVALID_FORMAT",
            "size,99999999999,size,INVALID_FORMAT"})
    void search_quandPaginationInvalide_alors400ValidationFailedSurLeChamp(
            String param, String value, String field, String code) throws Exception {
        // Given / When
        ResultActions result = mockMvc.perform(get(URL).param(param, value).with(member()));
        // Then
        assertValidationError(result, field, code);
        verifyNoInteractions(searchAvailableBooksUseCase);
    }

    @Test
    void search_quandQDeCentUnCaracteres_alors400TooLongSurQ() throws Exception {
        // Given / When
        ResultActions result = mockMvc.perform(get(URL).param("q", "a".repeat(101)).with(member()));
        // Then
        assertValidationError(result, "q", "TOO_LONG");
        verifyNoInteractions(searchAvailableBooksUseCase);
    }

    @Test
    void search_quandPlusieursParametresInvalides_alorsToutesLesErreursRenvoyeesEnsemble() throws Exception {
        // Given / When
        ResultActions result = mockMvc.perform(
                get(URL).param("page", "-1").param("size", "0").param("q", "a".repeat(101)).with(member()));
        // Then
        assertValidationError(result, "page", "OUT_OF_RANGE");
        assertValidationError(result, "size", "OUT_OF_RANGE");
        assertValidationError(result, "q", "TOO_LONG");
    }

    @Test
    void search_quandParametresInvalides_alorsMessagesFrancaisConformesAuContrat() throws Exception {
        // Given / When
        ResultActions result = mockMvc.perform(
                get(URL).param("page", "-1").param("size", "51").param("q", "a".repeat(101)).with(member()));
        // Then
        result.andExpect(jsonPath("$.errors[?(@.field=='page')].message")
                        .value("Le paramètre 'page' doit être un entier supérieur ou égal à 0"))
                .andExpect(jsonPath("$.errors[?(@.field=='size')].message")
                        .value("Le paramètre 'size' doit être un entier compris entre 1 et 50"))
                .andExpect(jsonPath("$.errors[?(@.field=='q')].message")
                        .value("Le paramètre 'q' est trop long (100 caractères maximum)"))
                .andExpect(jsonPath("$.title").value("Validation échouée"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void search_quandPageNonNumerique_alorsMessagePageEtPasDeMessagePost() throws Exception {
        // Given / When / Then : le message du champ 'page' ne doit pas être celui du POST
        mockMvc.perform(get(URL).param("page", "abc").with(member()))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Le paramètre 'page' doit être un entier supérieur ou égal à 0"));
    }

    @Test
    void search_quandMemberEtParametreInvalide_alors400PasDeFuiteDeStackTrace() throws Exception {
        // Given / When
        String content = mockMvc.perform(get(URL).param("size", "abc").with(member()))
                .andReturn().getResponse().getContentAsString();
        // Then
        assertThat(content).doesNotContain("Exception").doesNotContain("java.lang").doesNotContain("at com.example");
    }
}
