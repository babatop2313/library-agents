package com.example.library.catalog.adapter.in.mcp;

import java.util.List;

import com.example.library.catalog.application.port.in.GetBookByIsbnQuery;
import com.example.library.catalog.application.port.in.GetBookByIsbnUseCase;
import com.example.library.catalog.application.port.in.ListAvailableBooksQuery;
import com.example.library.catalog.application.port.in.ListAvailableBooksUseCase;
import com.example.library.catalog.domain.exception.DomainException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outils MCP du catalogue, en lecture seule. N'appelle que les ports d'entrée.
 * Toute erreur est renvoyée comme résultat d'outil en erreur (isError = true), avec un message en français.
 * Classe publique : l'invocation par réflexion du SDK MCP depuis un autre package l'exige.
 */
@Component
public class CatalogMcpTools {

    static final String GET_BOOK = "get_book";
    static final String LIST_AVAILABLE_BOOKS = "list_available_books";

    static final String GET_BOOK_DESCRIPTION = "Renvoie la fiche d'un livre du catalogue de la bibliothèque à partir "
            + "de son ISBN : isbn (13 chiffres, forme normalisée), title, author, availableCopies (exemplaires "
            + "empruntables maintenant) et totalCopies. À utiliser quand l'utilisateur donne un ISBN précis ou veut "
            + "savoir si ce livre est disponible. Ne pas utiliser pour chercher par titre, auteur ou mot-clé "
            + "(non supporté), ni pour parcourir le catalogue : utiliser list_available_books. Si aucun livre ne "
            + "porte cet ISBN, l'outil renvoie une erreur « Aucun livre trouvé ». Outil en lecture seule.";
    static final String ISBN_DESCRIPTION = "ISBN-13 du livre : exactement 13 chiffres ; tirets et espaces tolérés "
            + "(ex. 978-2-07-036822-8 ou 9782070368228). Les ISBN-10 ne sont pas acceptés. Obligatoire.";
    static final String LIST_DESCRIPTION = "Liste les livres du catalogue qui ont au moins un exemplaire disponible "
            + "à l'emprunt, triés par titre (sans tenir compte de la casse) puis par ISBN. Chaque élément contient "
            + "isbn, title, author, availableCopies et totalCopies. À utiliser pour proposer des livres empruntables "
            + "ou donner un aperçu du catalogue disponible. Ne pas utiliser pour un livre dont l'ISBN est connu "
            + "(utiliser get_book), ni pour chercher par titre ou auteur (non supporté). Les livres sans exemplaire "
            + "disponible n'apparaissent jamais. Une liste vide signifie qu'aucun livre n'est disponible. "
            + "Outil en lecture seule.";
    static final String LIMIT_DESCRIPTION = "Nombre maximal de livres à renvoyer : entier de 1 à 20. Facultatif, "
            + "10 si absent. Une valeur hors de 1 à 20 provoque une erreur (aucun ajustement automatique).";

    private static final Logger log = LoggerFactory.getLogger(CatalogMcpTools.class);

    private final GetBookByIsbnUseCase getBookByIsbnUseCase;
    private final ListAvailableBooksUseCase listAvailableBooksUseCase;
    private final JsonMapper jsonMapper;

    public CatalogMcpTools(GetBookByIsbnUseCase getBookByIsbnUseCase,
                           ListAvailableBooksUseCase listAvailableBooksUseCase,
                           JsonMapper jsonMapper) {
        this.getBookByIsbnUseCase = getBookByIsbnUseCase;
        this.listAvailableBooksUseCase = listAvailableBooksUseCase;
        this.jsonMapper = jsonMapper;
    }

    @McpTool(name = GET_BOOK, description = GET_BOOK_DESCRIPTION,
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult getBook(@McpToolParam(description = ISBN_DESCRIPTION, required = true) String isbn) {
        try {
            var book = getBookByIsbnUseCase.getByIsbn(GetBookByIsbnQuery.fromRaw(isbn));
            return success(BookViewMapper.from(book));
        } catch (DomainException e) {
            return error(McpToolErrors.messageFor(e));
        } catch (RuntimeException e) {
            return internalError(GET_BOOK, e);
        }
    }

    @McpTool(name = LIST_AVAILABLE_BOOKS, description = LIST_DESCRIPTION,
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult listAvailableBooks(
            @McpToolParam(description = LIMIT_DESCRIPTION, required = false) Integer limit) {
        try {
            List<BookView> views = listAvailableBooksUseCase.listAvailable(ListAvailableBooksQuery.of(limit))
                    .stream()
                    .map(BookViewMapper::from)
                    .toList();
            return success(views);
        } catch (DomainException e) {
            return error(McpToolErrors.messageFor(e));
        } catch (RuntimeException e) {
            return internalError(LIST_AVAILABLE_BOOKS, e);
        }
    }

    private CallToolResult success(Object payload) {
        return CallToolResult.builder()
                .addTextContent(jsonMapper.writeValueAsString(payload))
                .isError(false)
                .build();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private static CallToolResult internalError(String tool, RuntimeException cause) {
        // ni argument d'appel, ni en-tête, ni token dans le journal
        log.error("Erreur imprévue dans l'outil MCP {}", tool, cause);
        return error(McpToolErrors.INTERNAL_ERROR);
    }
}
