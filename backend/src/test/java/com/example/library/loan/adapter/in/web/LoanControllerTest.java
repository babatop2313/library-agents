package com.example.library.loan.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.library.config.security.SecurityConfig;
import com.example.library.config.security.TestJwts;
import com.example.library.loan.application.port.in.BorrowBookCommand;
import com.example.library.loan.application.port.in.BorrowBookUseCase;
import com.example.library.loan.application.port.in.CurrentLoanView;
import com.example.library.loan.application.port.in.ListCurrentLoansQuery;
import com.example.library.loan.application.port.in.ListCurrentLoansUseCase;
import com.example.library.loan.application.port.in.ReturnLoanCommand;
import com.example.library.loan.application.port.in.ReturnLoanUseCase;
import com.example.library.loan.config.LoanWebConfig;
import com.example.library.loan.domain.exception.BookAlreadyBorrowedException;
import com.example.library.loan.domain.exception.BookNotAvailableException;
import com.example.library.loan.domain.exception.BookNotFoundException;
import com.example.library.loan.domain.exception.LoanAlreadyReturnedException;
import com.example.library.loan.domain.exception.LoanLimitReachedException;
import com.example.library.loan.domain.exception.LoanNotFoundException;
import com.example.library.loan.domain.exception.MemberHasOverdueLoanException;
import com.example.library.loan.domain.model.BookId;
import com.example.library.loan.domain.model.Loan;
import com.example.library.loan.domain.model.LoanId;
import com.example.library.loan.domain.model.MemberId;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contrat HTTP des emprunts (RM-LOAN-13, 14, 18, 19, 20) : statuts, codes, JSON exact, sécurité réelle (JWT HS256
 * forgés par {@link TestJwts}), CSRF réel. Use cases simulés.
 */
@WebMvcTest(LoanController.class)
@Import({SecurityConfig.class, LoanWebConfig.class})
class LoanControllerTest {

    private static final String URL = "/api/v1/loans";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BorrowBookUseCase borrowBookUseCase;

    @MockitoBean
    private ListCurrentLoansUseCase listCurrentLoansUseCase;

    @MockitoBean
    private ReturnLoanUseCase returnLoanUseCase;

    private final UUID memberSub = UUID.randomUUID();
    private final UUID bookUuid = UUID.randomUUID();

    private ListAppender<ILoggingEvent> logs;
    private Logger handlerLogger;
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        handlerLogger = (Logger) LoggerFactory.getLogger(LoanExceptionHandler.class);
        logs = new ListAppender<>();
        logs.start();
        handlerLogger.addAppender(logs);
        originalLevel = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void releaseLogs() {
        handlerLogger.detachAppender(logs);
        handlerLogger.setLevel(originalLevel);
    }

    // ------------------------------------------------------------------ aides

    private String member() {
        return TestJwts.bearer(TestJwts.withRoles(memberSub.toString(), "membre@example.org", "MEMBER"));
    }

    private static String librarianOnly() {
        return TestJwts.bearer(TestJwts.withRoles(UUID.randomUUID().toString(), "bib@example.org", "LIBRARIAN"));
    }

    private static String librarianAndMember() {
        return TestJwts.bearer(TestJwts.withRoles(UUID.randomUUID().toString(), "bib@example.org", "LIBRARIAN", "MEMBER"));
    }

    private static String memberWithSubject(String subject) {
        return TestJwts.bearer(TestJwts.withRoles(subject, "membre@example.org", "MEMBER"));
    }

    private String body(String bookId) {
        return "{\"bookId\":" + bookId + "}";
    }

    private String validBody() {
        return body("\"" + bookUuid + "\"");
    }

    private ResultActions postLoan(String authorization, String body) throws Exception {
        return mockMvc.perform(post(URL).header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions getLoans(String authorization) throws Exception {
        return mockMvc.perform(get(URL).header("Authorization", authorization));
    }

    private ResultActions returnLoan(String authorization, String id) throws Exception {
        return mockMvc.perform(post(URL + "/" + id + "/return").header("Authorization", authorization));
    }

    private Loan givenCreatedLoan() {
        Loan loan = Loan.open(new MemberId(memberSub), new BookId(bookUuid), TODAY);
        when(borrowBookUseCase.borrow(any())).thenReturn(loan);
        return loan;
    }

    private void assertNoUseCaseCalled() {
        verifyNoInteractions(borrowBookUseCase, listCurrentLoansUseCase, returnLoanUseCase);
    }

    private ResultActions assertValidationError(ResultActions result, String field, String code) throws Exception {
        return result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    // ------------------------------------------------------------------ POST /api/v1/loans : succès

    @Test
    void borrow_quandMembreEtCorpsValide_alors201LocationEtJsonExact() throws Exception {
        // Given
        Loan loan = givenCreatedLoan();
        String id = loan.getId().value().toString();
        // When / Then : dates ISO sans fuseau, quatre champs exactement
        postLoan(member(), validBody())
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/loans/" + id)))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.bookId").value(bookUuid.toString()))
                .andExpect(jsonPath("$.borrowedOn").value("2026-11-24"))
                .andExpect(jsonPath("$.dueOn").value("2026-12-08"))
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void borrow_quandMembre_alorsCommandeConstruiteAvecLeSubDuJetonEtLeBookIdDuCorps() throws Exception {
        // Given
        givenCreatedLoan();
        // When
        postLoan(member(), validBody()).andExpect(status().isCreated());
        // Then
        ArgumentCaptor<BorrowBookCommand> captor = ArgumentCaptor.forClass(BorrowBookCommand.class);
        verify(borrowBookUseCase).borrow(captor.capture());
        assertThatCommand(captor.getValue(), new MemberId(memberSub), new BookId(bookUuid));
    }

    @Test
    void borrow_quandCorpsContientUnMemberIdDUnAutre_alorsChampIgnoreEtMembreDuJeton() throws Exception {
        // Given : RM-LOAN-19, aucun IDOR possible
        givenCreatedLoan();
        UUID victim = UUID.randomUUID();
        String forged = "{\"bookId\":\"" + bookUuid + "\",\"memberId\":\"" + victim + "\"}";
        // When
        postLoan(member(), forged).andExpect(status().isCreated());
        // Then
        ArgumentCaptor<BorrowBookCommand> captor = ArgumentCaptor.forClass(BorrowBookCommand.class);
        verify(borrowBookUseCase).borrow(captor.capture());
        assertThatCommand(captor.getValue(), new MemberId(memberSub), new BookId(bookUuid));
    }

    private static void assertThatCommand(BorrowBookCommand command, MemberId member, BookId book) {
        org.assertj.core.api.Assertions.assertThat(command.memberId()).isEqualTo(member);
        org.assertj.core.api.Assertions.assertThat(command.bookId()).isEqualTo(book);
    }

    @Test
    void borrow_quandLibrarianEtMember_alors201() throws Exception {
        // Given : un compte qui cumule les rôles emprunte pour lui-même
        givenCreatedLoan();
        // When / Then
        postLoan(librarianAndMember(), validBody()).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ POST : validation (400)

    @Test
    void borrow_quandCorpsVideObjet_alors400BookIdRequired() throws Exception {
        // When / Then
        assertValidationError(postLoan(member(), "{}"), "bookId", "REQUIRED");
        assertNoUseCaseCalled();
    }

    @Test
    void borrow_quandBookIdNull_alors400BookIdRequired() throws Exception {
        // When / Then
        assertValidationError(postLoan(member(), body("null")), "bookId", "REQUIRED");
        assertNoUseCaseCalled();
    }

    @Test
    void borrow_quandBookIdNonUuid_alors400BookIdInvalidFormat() throws Exception {
        // When / Then
        assertValidationError(postLoan(member(), body("\"abc\"")), "bookId", "INVALID_FORMAT");
        assertNoUseCaseCalled();
    }

    @Test
    void borrow_quandBookIdDeTypeNombre_alors400BookIdInvalidFormat() throws Exception {
        // When / Then
        assertValidationError(postLoan(member(), body("123")), "bookId", "INVALID_FORMAT");
        assertNoUseCaseCalled();
    }

    @ParameterizedTest
    @MethodSource("unreadableBodies")
    void borrow_quandJsonIllisible_alors400ValidationFailedSansErreurParChamp(String unreadable) throws Exception {
        // When / Then : D2, errors vide
        postLoan(member(), unreadable)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", empty()));
        assertNoUseCaseCalled();
    }

    static Stream<String> unreadableBodies() {
        return Stream.of("{\"bookId\":", "pas du json", "[1,2");
    }

    @Test
    void borrow_quandAucunCorps_alors400ValidationFailed() throws Exception {
        // When / Then
        mockMvc.perform(post(URL).header("Authorization", member()).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertNoUseCaseCalled();
    }

    // ------------------------------------------------------------------ POST : refus métier (404, 409)

    static Stream<Arguments> borrowRefusals() {
        UUID id = UUID.randomUUID();
        return Stream.of(
                Arguments.of(new BookNotFoundException(new BookId(id)), 404, "BOOK_NOT_FOUND"),
                Arguments.of(new MemberHasOverdueLoanException(), 409, "MEMBER_HAS_OVERDUE_LOAN"),
                Arguments.of(new LoanLimitReachedException(3), 409, "LOAN_LIMIT_REACHED"),
                Arguments.of(new BookAlreadyBorrowedException(), 409, "BOOK_ALREADY_BORROWED"),
                Arguments.of(new BookNotAvailableException(new BookId(id)), 409, "BOOK_NOT_AVAILABLE"));
    }

    @ParameterizedTest
    @MethodSource("borrowRefusals")
    void borrow_quandRefusMetier_alorsStatutEtCodeDuContrat(RuntimeException refusal, int status, String code)
            throws Exception {
        // Given
        when(borrowBookUseCase.borrow(any())).thenThrow(refusal);
        // When / Then
        postLoan(member(), validBody())
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void borrow_quandRefusMetier_alorsWarnAvecLeCodeSeulementSansDonneePersonnelle() throws Exception {
        // Given
        when(borrowBookUseCase.borrow(any())).thenThrow(new BookNotAvailableException(new BookId(bookUuid)));
        // When
        postLoan(member(), validBody()).andExpect(status().isConflict());
        // Then : RM-LOAN-20
        assertThat409Warn("BOOK_NOT_AVAILABLE");
    }

    private void assertThat409Warn(String code) {
        List<ILoggingEvent> warns = logs.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        org.assertj.core.api.Assertions.assertThat(warns).hasSize(1);
        String message = warns.get(0).getFormattedMessage();
        org.assertj.core.api.Assertions.assertThat(message).contains(code)
                .doesNotContain(memberSub.toString()).doesNotContain(bookUuid.toString())
                .doesNotContain("@");
    }

    // ------------------------------------------------------------------ GET /api/v1/loans

    @Test
    void list_quandEmpruntsEnCours_alors200AvecTousLesChampsDuContrat() throws Exception {
        // Given
        LoanId id = LoanId.newId();
        when(listCurrentLoansUseCase.listCurrentLoans(any())).thenReturn(List.of(
                new CurrentLoanView(id, new BookId(bookUuid), "9782070368228", "L'Étranger", "Albert Camus",
                        TODAY.minusDays(10), TODAY.plusDays(4), false),
                new CurrentLoanView(LoanId.newId(), new BookId(UUID.randomUUID()), null, null, null,
                        TODAY.minusDays(20), TODAY.minusDays(6), true)));
        // When / Then
        getLoans(member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(id.value().toString()))
                .andExpect(jsonPath("$[0].bookId").value(bookUuid.toString()))
                .andExpect(jsonPath("$[0].isbn").value("9782070368228"))
                .andExpect(jsonPath("$[0].title").value("L'Étranger"))
                .andExpect(jsonPath("$[0].author").value("Albert Camus"))
                .andExpect(jsonPath("$[0].borrowedOn").value("2026-11-14"))
                .andExpect(jsonPath("$[0].dueOn").value("2026-11-28"))
                .andExpect(jsonPath("$[0].overdue").value(false))
                .andExpect(jsonPath("$[0].length()").value(8))
                .andExpect(jsonPath("$[1].overdue").value(true));
    }

    @Test
    void list_quandLivreIntrouvable_alorsChampsDuLivreNulsMaisPresents() throws Exception {
        // Given : RM-LOAN-32
        when(listCurrentLoansUseCase.listCurrentLoans(any())).thenReturn(List.of(
                new CurrentLoanView(LoanId.newId(), new BookId(bookUuid), null, null, null,
                        TODAY.minusDays(1), TODAY.plusDays(13), false)));
        // When / Then
        getLoans(member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].isbn").value(nullValue()))
                .andExpect(jsonPath("$[0].title").value(nullValue()))
                .andExpect(jsonPath("$[0].author").value(nullValue()))
                .andExpect(content().string(containsString("\"isbn\":null")));
    }

    @Test
    void list_quandAucunEmprunt_alors200TableauVide() throws Exception {
        // Given
        when(listCurrentLoansUseCase.listCurrentLoans(any())).thenReturn(List.of());
        // When / Then
        getLoans(member()).andExpect(status().isOk()).andExpect(content().json("[]", true));
    }

    @Test
    void list_quandMembre_alorsRequeteConstruiteAvecLeSubDuJeton() throws Exception {
        // Given
        when(listCurrentLoansUseCase.listCurrentLoans(any())).thenReturn(List.of());
        // When
        getLoans(member()).andExpect(status().isOk());
        // Then
        ArgumentCaptor<ListCurrentLoansQuery> captor = ArgumentCaptor.forClass(ListCurrentLoansQuery.class);
        verify(listCurrentLoansUseCase).listCurrentLoans(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().memberId()).isEqualTo(new MemberId(memberSub));
    }

    // ------------------------------------------------------------------ POST /api/v1/loans/{id}/return

    @Test
    void giveBack_quandEmpruntDuMembre_alors204SansCorpsEtCommandeAvecLeSub() throws Exception {
        // Given
        UUID loanUuid = UUID.randomUUID();
        // When
        returnLoan(member(), loanUuid.toString())
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        // Then
        ArgumentCaptor<ReturnLoanCommand> captor = ArgumentCaptor.forClass(ReturnLoanCommand.class);
        verify(returnLoanUseCase).giveBack(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().memberId()).isEqualTo(new MemberId(memberSub));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().loanId()).isEqualTo(new LoanId(loanUuid));
    }

    @Test
    void giveBack_quandIdentifiantNonUuid_alors404LoanNotFoundSansAppelAuUseCase() throws Exception {
        // When / Then : RM-LOAN-19
        returnLoan(member(), "abc")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"));
        assertNoUseCaseCalled();
    }

    static Stream<Arguments> returnRefusals() {
        return Stream.of(
                Arguments.of(new LoanNotFoundException(), 404, "LOAN_NOT_FOUND"),
                Arguments.of(new LoanAlreadyReturnedException(LoanId.newId()), 409, "LOAN_ALREADY_RETURNED"));
    }

    @ParameterizedTest
    @MethodSource("returnRefusals")
    void giveBack_quandRefusMetier_alorsStatutEtCodeDuContrat(RuntimeException refusal, int status, String code)
            throws Exception {
        // Given
        org.mockito.Mockito.doThrow(refusal).when(returnLoanUseCase).giveBack(any());
        // When / Then
        returnLoan(member(), UUID.randomUUID().toString())
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    // ------------------------------------------------------------------ sécurité : 401

    @Test
    void endpoints_quandAucunJeton_alors401Unauthenticated() throws Exception {
        // When / Then : RM-LOAN-18, les trois endpoints
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post(URL + "/" + UUID.randomUUID() + "/return"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertNoUseCaseCalled();
    }

    @Test
    void endpoints_quandJetonExpireOuSigneParUneAutreCle_alors401() throws Exception {
        // When / Then
        postLoan(TestJwts.bearer(TestJwts.expired()), validBody()).andExpect(status().isUnauthorized());
        getLoans(TestJwts.bearer(TestJwts.signedWithOtherKey())).andExpect(status().isUnauthorized());
        assertNoUseCaseCalled();
    }

    @ParameterizedTest
    @MethodSource("nonUuidSubjects")
    void endpoints_quandSubNonUuid_alors401UnauthenticatedPourLesTroisEndpointsSansAppelAuUseCase(String subject)
            throws Exception {
        // Given : jeton valide, rôle MEMBER, sub inexploitable
        String authorization = memberWithSubject(subject);
        // When / Then : le sub est résolu avant le corps et le chemin : 401, jamais 400 ni 404
        postLoan(authorization, validBody())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
        getLoans(authorization)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        returnLoan(authorization, UUID.randomUUID().toString())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertNoUseCaseCalled();
    }

    static Stream<String> nonUuidSubjects() {
        return Stream.of("not-a-uuid", "12345", "00000000-0000-0000-0000-00000000000Z", "1-2-3-4-5", "1-1-1-1-1",
                "00000000-0000-0000-0000-0000000000001", "{00000000-0000-0000-0000-000000000001}", " ");
    }

    @Test
    void borrow_quandSubNonUuidEtCorpsInvalide_alors401AvantToute400() throws Exception {
        // When / Then
        postLoan(memberWithSubject("not-a-uuid"), "{}").andExpect(status().isUnauthorized());
        postLoan(memberWithSubject("not-a-uuid"), "pas du json").andExpect(status().isUnauthorized());
        returnLoan(memberWithSubject("not-a-uuid"), "abc").andExpect(status().isUnauthorized());
        assertNoUseCaseCalled();
    }

    // ------------------------------------------------------------------ sécurité : 403

    @Test
    void endpoints_quandLibrarianSeul_alors403AccessDeniedPourLesTroisEndpoints() throws Exception {
        // When / Then
        postLoan(librarianOnly(), validBody())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        getLoans(librarianOnly())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        returnLoan(librarianOnly(), UUID.randomUUID().toString())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertNoUseCaseCalled();
    }

    @Test
    void endpoints_quandLibrarianSeulAvecCorpsInvalideOuIdentifiantMalForme_alors403AvantValidation() throws Exception {
        // When / Then : la règle d'URL de SecurityConfig est évaluée avant la validation et la résolution des arguments
        postLoan(librarianOnly(), "{}").andExpect(status().isForbidden());
        postLoan(librarianOnly(), "pas du json").andExpect(status().isForbidden());
        returnLoan(librarianOnly(), "abc").andExpect(status().isForbidden());
        assertNoUseCaseCalled();
    }

    @Test
    void endpoints_quandLibrarianSeulAvecSubNonUuid_alors403() throws Exception {
        // Given
        String authorization = TestJwts.bearer(TestJwts.withRoles("not-a-uuid", "bib@example.org", "LIBRARIAN"));
        // When / Then
        postLoan(authorization, validBody()).andExpect(status().isForbidden());
        getLoans(authorization).andExpect(status().isForbidden());
        assertNoUseCaseCalled();
    }

    @Test
    void endpoints_quandJetonSansAucunRole_alors403() throws Exception {
        // Given
        String authorization = TestJwts.bearer(TestJwts.withRoles(memberSub.toString(), "x@example.org"));
        // When / Then
        getLoans(authorization).andExpect(status().isForbidden());
        assertNoUseCaseCalled();
    }

    // ------------------------------------------------------------------ sécurité : CSRF

    private MockHttpServletRequestBuilder withCookie(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("ACCESS_TOKEN",
                TestJwts.withRoles(memberSub.toString(), "membre@example.org", "MEMBER")));
    }

    @Test
    void borrow_quandCookieSansEnTeteCsrf_alors403AccessDenied() throws Exception {
        // When / Then
        mockMvc.perform(withCookie(post(URL)).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertNoUseCaseCalled();
    }

    @Test
    void giveBack_quandCookieSansEnTeteCsrf_alors403AccessDenied() throws Exception {
        // When / Then
        mockMvc.perform(withCookie(post(URL + "/" + UUID.randomUUID() + "/return")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertNoUseCaseCalled();
    }

    @Test
    void borrow_quandCookieAvecJetonCsrfValide_alors201() throws Exception {
        // Given
        givenCreatedLoan();
        // When / Then : double soumission du cookie XSRF-TOKEN dans l'en-tête X-XSRF-TOKEN
        mockMvc.perform(withCookie(post(URL)).cookie(new Cookie("XSRF-TOKEN", "csrf-value"))
                        .header("X-XSRF-TOKEN", "csrf-value")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated());
    }

    @Test
    void borrow_quandCookieAvecJetonCsrfDifferent_alors403() throws Exception {
        // When / Then
        mockMvc.perform(withCookie(post(URL)).cookie(new Cookie("XSRF-TOKEN", "csrf-value"))
                        .header("X-XSRF-TOKEN", "autre-valeur")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isForbidden());
        assertNoUseCaseCalled();
    }

    @Test
    void borrow_quandBearerSansCsrf_alors201() throws Exception {
        // Given : les clients non navigateurs sont dispensés de CSRF
        givenCreatedLoan();
        // When / Then
        postLoan(member(), validBody()).andExpect(status().isCreated());
    }

    @Test
    void list_quandCookieSansCsrf_alors200() throws Exception {
        // Given : une lecture n'exige pas le CSRF
        when(listCurrentLoansUseCase.listCurrentLoans(any())).thenReturn(List.of());
        // When / Then
        mockMvc.perform(withCookie(get(URL))).andExpect(status().isOk());
    }

    @Test
    void list_quandSubUuidCanoniqueEnMajuscules_alors200AvecLeMembreEnMinuscules() throws Exception {
        // Given : la forme canonique est acceptée quelle que soit la casse
        when(listCurrentLoansUseCase.listCurrentLoans(any())).thenReturn(List.of());
        // When
        getLoans(memberWithSubject(memberSub.toString().toUpperCase())).andExpect(status().isOk());
        // Then
        ArgumentCaptor<ListCurrentLoansQuery> captor = ArgumentCaptor.forClass(ListCurrentLoansQuery.class);
        verify(listCurrentLoansUseCase).listCurrentLoans(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().memberId()).isEqualTo(new MemberId(memberSub));
    }

    // ------------------------------------------------------------------ 503 LOAN_BUSY

    static Stream<RuntimeException> lockFailures() {
        return Stream.of(
                new org.springframework.dao.CannotAcquireLockException("verrou SQL secret"),
                new org.springframework.dao.PessimisticLockingFailureException("verrou SQL secret"),
                new org.springframework.dao.QueryTimeoutException("verrou SQL secret"));
    }

    private ResultActions assertBusy(ResultActions result) throws Exception {
        return result.andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("LOAN_BUSY"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret"))));
    }

    @ParameterizedTest
    @MethodSource("lockFailures")
    void borrow_quandVerrouIndisponible_alors503LoanBusyAvecRetryAfter(RuntimeException failure) throws Exception {
        // Given
        when(borrowBookUseCase.borrow(any())).thenThrow(failure);
        // When / Then : aucun détail technique exposé
        assertBusy(postLoan(member(), validBody()));
    }

    @ParameterizedTest
    @MethodSource("lockFailures")
    void giveBack_quandVerrouIndisponible_alors503LoanBusyAvecRetryAfter(RuntimeException failure) throws Exception {
        // Given
        org.mockito.Mockito.doThrow(failure).when(returnLoanUseCase).giveBack(any());
        // When / Then
        assertBusy(returnLoan(member(), UUID.randomUUID().toString()));
    }

    @Test
    void borrow_quandVerrouIndisponible_alorsWarnAvecLaClasseDeLExceptionSeulement() throws Exception {
        // Given
        when(borrowBookUseCase.borrow(any())).thenThrow(new org.springframework.dao.CannotAcquireLockException("verrou SQL secret"));
        // When
        assertBusy(postLoan(member(), validBody()));
        // Then
        List<ILoggingEvent> warns = logs.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        org.assertj.core.api.Assertions.assertThat(warns).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(warns.get(0).getFormattedMessage())
                .contains("CannotAcquireLockException").doesNotContain("secret")
                .doesNotContain(memberSub.toString()).doesNotContain(bookUuid.toString());
        org.assertj.core.api.Assertions.assertThat(warns.get(0).getThrowableProxy()).isNull();
    }

    @Test
    void giveBack_quandRefusMetier_alorsWarnAvecLeNouveauGabarit() throws Exception {
        // Given
        org.mockito.Mockito.doThrow(new LoanNotFoundException()).when(returnLoanUseCase).giveBack(any());
        // When
        returnLoan(member(), UUID.randomUUID().toString()).andExpect(status().isNotFound());
        // Then
        org.assertj.core.api.Assertions.assertThat(logs.list)
                .anySatisfy(event -> org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage())
                        .isEqualTo("Requête d'emprunt refusée : code=LOAN_NOT_FOUND"));
    }
}
