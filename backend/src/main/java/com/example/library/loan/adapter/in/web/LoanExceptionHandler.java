package com.example.library.loan.adapter.in.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.library.loan.domain.exception.BookAlreadyBorrowedException;
import com.example.library.loan.domain.exception.BookNotAvailableException;
import com.example.library.loan.domain.exception.BookNotFoundException;
import com.example.library.loan.domain.exception.DomainException;
import com.example.library.loan.domain.exception.LoanAlreadyReturnedException;
import com.example.library.loan.domain.exception.LoanLimitReachedException;
import com.example.library.loan.domain.exception.LoanNotFoundException;
import com.example.library.loan.domain.exception.MemberHasOverdueLoanException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Traduit les erreurs des emprunts en ProblemDetail (RFC 9457) avec la propriété "code" (et "errors" pour la
 * validation). Refus métier : 409 ; ressource introuvable : 404. Le détail ne mentionne jamais de SQL ni de nom de
 * contrainte. Les journaux ne contiennent que le code métier, aucune donnée personnelle.
 */
@RestControllerAdvice(basePackageClasses = LoanController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class LoanExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LoanExceptionHandler.class);

    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String REQUIRED = "REQUIRED";
    private static final String INVALID_FORMAT = "INVALID_FORMAT";

    /** Titres affichables, par code métier (le détail est le message de l'exception). */
    private static final Map<String, String> TITLES = Map.of(
            "BOOK_NOT_FOUND", "Livre introuvable",
            "LOAN_NOT_FOUND", "Emprunt introuvable",
            "MEMBER_HAS_OVERDUE_LOAN", "Emprunt en retard",
            "LOAN_LIMIT_REACHED", "Limite d'emprunts atteinte",
            "BOOK_ALREADY_BORROWED", "Livre déjà emprunté",
            "BOOK_NOT_AVAILABLE", "Livre indisponible",
            "LOAN_ALREADY_RETURNED", "Emprunt déjà rendu");

    record ApiError(String field, String code, String message) {
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalidBody(MethodArgumentNotValidException e) {
        Set<ApiError> errors = new LinkedHashSet<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.add(toApiError(fieldError.getField(),
                    fieldError.getRejectedValue() == null ? REQUIRED : INVALID_FORMAT));
        }
        return validationProblem(new ArrayList<>(errors));
    }

    /** JSON illisible : {@code errors} vide ; type incompatible sur un champ : INVALID_FORMAT pour ce champ. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
        List<ApiError> errors = new ArrayList<>();
        if (e.getCause() instanceof MismatchedInputException mismatch) {
            String field = pathOf(mismatch);
            if (!field.isEmpty()) {
                errors.add(toApiError(field, INVALID_FORMAT));
            }
        }
        return validationProblem(errors);
    }

    /** Identifiant d'emprunt mal formé dans l'URL : même réponse qu'un emprunt inconnu (pas de fuite d'information). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        if ("id".equals(e.getName())) {
            return handleNotFound(new LoanNotFoundException());
        }
        return validationProblem(List.of(toApiError(e.getName(), INVALID_FORMAT)));
    }

    @ExceptionHandler({BookNotFoundException.class, LoanNotFoundException.class})
    ProblemDetail handleNotFound(DomainException e) {
        return problem(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler({MemberHasOverdueLoanException.class, LoanLimitReachedException.class,
            BookAlreadyBorrowedException.class, BookNotAvailableException.class, LoanAlreadyReturnedException.class})
    ProblemDetail handleRefusal(DomainException e) {
        return problem(HttpStatus.CONFLICT, e);
    }

    /** Jeton valide mais {@code sub} non UUID : même réponse que l'absence de jeton (401, WWW-Authenticate). */
    @ExceptionHandler(InvalidMemberIdentityException.class)
    ResponseEntity<ProblemDetail> handleInvalidIdentity(InvalidMemberIdentityException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Authentification requise");
        problem.setProperty("code", "UNAUTHENTICATED");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem);
    }

    /**
     * Délai de verrou dépassé (SQLState 55P03 : verrou consultatif du membre ou ligne verrouillée) : le service est
     * momentanément occupé, la requête peut être rejouée. Aucun détail technique n'est exposé.
     */
    @ExceptionHandler({PessimisticLockingFailureException.class, CannotAcquireLockException.class,
            QueryTimeoutException.class})
    ResponseEntity<ProblemDetail> handleBusy(Exception e) {
        log.warn("Requête d'emprunt en attente de verrou trop longue : cause={}", e.getClass().getSimpleName());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Réessayez dans quelques instants");
        problem.setTitle("Service momentanément occupé");
        problem.setProperty("code", "LOAN_BUSY");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, DomainException e) {
        log.warn("Requête d'emprunt refusée : code={}", e.getCode());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(TITLES.getOrDefault(e.getCode(), status.getReasonPhrase()));
        problem.setProperty("code", e.getCode());
        return problem;
    }

    private static ProblemDetail validationProblem(List<ApiError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "La requête est invalide");
        problem.setTitle("Validation échouée");
        problem.setProperty("code", VALIDATION_FAILED);
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ApiError toApiError(String field, String code) {
        String message = REQUIRED.equals(code)
                ? "Le champ '" + field + "' est obligatoire"
                : "Le champ '" + field + "' a un format invalide";
        return new ApiError(field, code, message);
    }

    private static String pathOf(JacksonException e) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : e.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getPropertyName());
            } else {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
