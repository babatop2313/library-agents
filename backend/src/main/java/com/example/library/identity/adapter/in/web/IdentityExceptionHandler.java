package com.example.library.identity.adapter.in.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.library.identity.domain.exception.InvalidCredentialsException;
import com.example.library.identity.domain.exception.InvalidEmailException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Traduit les erreurs d'authentification en ProblemDetail (RFC 9457) avec la propriété "code".
 * Le détail ne dit jamais si le compte existe, et ne reprend aucune valeur saisie.
 */
@RestControllerAdvice(basePackageClasses = AuthController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class IdentityExceptionHandler {

    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    record ApiError(String field, String code, String message) {
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalidBody(MethodArgumentNotValidException e) {
        Set<ApiError> errors = new LinkedHashSet<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.add(toApiError(fieldError.getField(), codeOf(fieldError)));
        }
        return validationProblem(new ArrayList<>(errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
        List<ApiError> errors = new ArrayList<>();
        if (e.getCause() instanceof MismatchedInputException mismatch) {
            errors.add(toApiError(pathOf(mismatch), ApiErrorCode.INVALID_FORMAT));
        }
        return validationProblem(errors);
    }

    /** Défense : la validation web a normalement déjà intercepté ce cas. */
    @ExceptionHandler(InvalidEmailException.class)
    ProblemDetail handleInvalidEmail(InvalidEmailException e) {
        return validationProblem(List.of(toApiError("email", ApiErrorCode.INVALID_FORMAT)));
    }

    /** 401 : en-tête WWW-Authenticate obligatoire (RFC 9110), comme sur les autres 401 de l'API. */
    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("Identifiants invalides");
        problem.setProperty("code", e.getCode());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem);
    }

    private static ProblemDetail validationProblem(List<ApiError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "La requête est invalide");
        problem.setTitle("Validation échouée");
        problem.setProperty("code", VALIDATION_FAILED);
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ApiErrorCode codeOf(FieldError error) {
        if (error.getRejectedValue() == null) {
            return ApiErrorCode.REQUIRED;
        }
        String constraint = error.getCode() == null ? "" : error.getCode();
        return switch (constraint) {
            case "NotNull" -> ApiErrorCode.REQUIRED;
            case "NotBlank", "NotEmpty" -> ApiErrorCode.BLANK;
            case "MaxTrimmedLength" -> ApiErrorCode.TOO_LONG;
            default -> ApiErrorCode.INVALID_FORMAT;
        };
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

    private static ApiError toApiError(String field, ApiErrorCode code) {
        return new ApiError(field, code.name(), messageOf(field, code));
    }

    private static String messageOf(String field, ApiErrorCode code) {
        return switch (code) {
            case REQUIRED -> "Le champ '" + field + "' est obligatoire";
            case BLANK -> "Le champ '" + field + "' ne doit pas être vide";
            case TOO_LONG -> "Le champ '" + field + "' est trop long";
            case INVALID_FORMAT -> "Le champ '" + field + "' a un format invalide";
        };
    }
}
