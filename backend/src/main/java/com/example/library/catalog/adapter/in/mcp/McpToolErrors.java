package com.example.library.catalog.adapter.in.mcp;

import com.example.library.catalog.domain.exception.BookNotFoundException;
import com.example.library.catalog.domain.exception.DomainException;
import com.example.library.catalog.domain.model.AvailableBooksLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Messages d'erreur destinés à l'assistant, en français. Le message est choisi d'après le code métier :
 * jamais le message de l'exception, un nom de classe ou une stack trace.
 */
final class McpToolErrors {

    static final String INVALID_ISBN =
            "ISBN invalide : il doit comporter 13 chiffres (tirets et espaces tolérés), par exemple 9782070368228.";
    static final String INVALID_LIMIT = "limit doit être un entier compris entre " + AvailableBooksLimit.MIN
            + " et " + AvailableBooksLimit.MAX + " (" + AvailableBooksLimit.DEFAULT + " par défaut si absent).";
    static final String INTERNAL_ERROR = "Erreur interne, réessayez plus tard.";

    private static final Logger log = LoggerFactory.getLogger(McpToolErrors.class);

    private McpToolErrors() {
    }

    static String messageFor(DomainException error) {
        return messageFor(null, error);
    }

    /** {@code toolName} peut être nul ; il ne sert qu'au journal. */
    static String messageFor(String toolName, DomainException error) {
        return switch (error.getCode()) {
            case "ISBN_INVALID" -> INVALID_ISBN;
            case "BOOK_NOT_FOUND" -> error instanceof BookNotFoundException notFound
                    ? "Aucun livre trouvé pour l'ISBN " + notFound.getIsbn().value() + "."
                    : "Aucun livre trouvé pour cet ISBN.";
            case "LIMIT_OUT_OF_RANGE" -> INVALID_LIMIT;
            default -> {
                // code seul : jamais le message de l'exception ni de donnée personnelle
                log.warn("Code d'erreur métier non prévu dans l'outil MCP {} : {}",
                        toolName == null ? "inconnu" : toolName, error.getCode());
                yield INTERNAL_ERROR;
            }
        };
    }
}
