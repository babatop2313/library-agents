package com.example.library.catalog.adapter.in.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.library.catalog.domain.exception.InvalidBookException;
import com.example.library.catalog.domain.exception.InvalidIsbnException;
import com.example.library.catalog.domain.exception.IsbnAlreadyExistsException;
import com.example.library.catalog.domain.model.ViolationCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Traduit les erreurs du catalogue en ProblemDetail (RFC 9457) avec les propriétés "code" et "errors".
 * Le détail ne mentionne jamais de SQL ni de nom de contrainte.
 */
@RestControllerAdvice(basePackageClasses = BookController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class CatalogExceptionHandler {

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
            errors.add(toApiError(pathOf(mismatch), ViolationCode.INVALID_FORMAT));
        }
        return validationProblem(errors);
    }

    /** Défense : la validation web a normalement déjà intercepté ces cas. */
    @ExceptionHandler(InvalidBookException.class)
    ProblemDetail handleInvalidBook(InvalidBookException e) {
        List<ApiError> errors = e.violations().stream()
                .map(v -> toApiError(v.field(), v.code()))
                .distinct()
                .toList();
        return validationProblem(errors);
    }

    /** Défense : la validation web a normalement déjà intercepté ce cas. */
    @ExceptionHandler(InvalidIsbnException.class)
    ProblemDetail handleInvalidIsbn(InvalidIsbnException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("ISBN invalide");
        problem.setProperty("code", e.getCode());
        return problem;
    }

    @ExceptionHandler(IsbnAlreadyExistsException.class)
    ProblemDetail handleIsbnAlreadyExists(IsbnAlreadyExistsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("ISBN déjà enregistré");
        problem.setProperty("code", e.getCode());
        return problem;
    }

    private static ProblemDetail validationProblem(List<ApiError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "La requête est invalide");
        problem.setTitle("Validation échouée");
        problem.setProperty("code", VALIDATION_FAILED);
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ViolationCode codeOf(FieldError error) {
        if (error.getRejectedValue() == null) {
            return ViolationCode.REQUIRED;
        }
        String constraint = error.getCode() == null ? "" : error.getCode();
        return switch (constraint) {
            case "NotNull" -> ViolationCode.REQUIRED;
            case "NotBlank" -> ViolationCode.BLANK;
            case "ValidIsbnFormat" -> ViolationCode.INVALID_FORMAT;
            case "MaxTrimmedLength" -> ViolationCode.TOO_LONG;
            case "Min", "Max" -> ViolationCode.OUT_OF_RANGE;
            default -> ViolationCode.INVALID_FORMAT;
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

    private static ApiError toApiError(String field, ViolationCode code) {
        return new ApiError(field, code.name(), messageOf(field, code));
    }

    private static String messageOf(String field, ViolationCode code) {
        return switch (code) {
            case REQUIRED -> "Le champ '" + field + "' est obligatoire";
            case BLANK -> "Le champ '" + field + "' ne doit pas être vide";
            case TOO_LONG -> "Le champ '" + field + "' est trop long (255 caractères maximum)";
            case INVALID_FORMAT -> "Le champ '" + field + "' a un format invalide";
            case OUT_OF_RANGE -> "Le champ '" + field + "' est hors des bornes autorisées";
        };
    }
}
