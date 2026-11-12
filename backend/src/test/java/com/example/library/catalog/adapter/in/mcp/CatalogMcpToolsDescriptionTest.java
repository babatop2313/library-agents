package com.example.library.catalog.adapter.in.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/** RM-MCP-01 et RM-MCP-12 : deux outils exactement, decrits, en lecture seule (controle automatique minimal). */
class CatalogMcpToolsDescriptionTest {

    private static List<Method> toolMethods() {
        return Arrays.stream(CatalogMcpTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(McpTool.class))
                .toList();
    }

    @Test
    void outils_quandClasseInspectee_alorsExactementGetBookEtListAvailableBooks() {
        // When
        List<String> names = toolMethods().stream().map(m -> m.getAnnotation(McpTool.class).name()).toList();
        // Then
        assertThat(names).containsExactlyInAnyOrder("get_book", "list_available_books");
    }

    @Test
    void outils_quandInspectes_alorsDescriptionNonVide() {
        // When / Then
        assertThat(toolMethods()).allSatisfy(m ->
                assertThat(m.getAnnotation(McpTool.class).description()).isNotBlank());
    }

    @Test
    void parametres_quandInspectes_alorsChacunDecritNonVide() {
        // When / Then
        assertThat(toolMethods()).allSatisfy(m -> {
            assertThat(m.getParameters()).isNotEmpty();
            for (Parameter p : m.getParameters()) {
                McpToolParam param = p.getAnnotation(McpToolParam.class);
                assertThat(param).as("@McpToolParam sur %s.%s", m.getName(), p.getName()).isNotNull();
                assertThat(param.description()).isNotBlank();
            }
        });
    }

    @Test
    void outils_quandInspectes_alorsLectureSeuleNonDestructifs() {
        // When / Then
        assertThat(toolMethods()).allSatisfy(m -> {
            McpTool.McpAnnotations hints = m.getAnnotation(McpTool.class).annotations();
            assertThat(hints.readOnlyHint()).isTrue();
            assertThat(hints.destructiveHint()).isFalse();
            assertThat(hints.idempotentHint()).isTrue();
        });
    }

    @Test
    void descriptions_quandRedigeesPourLeModele_alorsMentionnentFormatEtBornes() {
        // When / Then
        assertThat(CatalogMcpTools.ISBN_DESCRIPTION).contains("13 chiffres").contains("tirets");
        assertThat(CatalogMcpTools.LIMIT_DESCRIPTION).contains("1 à 20").contains("10");
        assertThat(CatalogMcpTools.GET_BOOK_DESCRIPTION).contains("Aucun livre trouvé").contains("Ne pas utiliser");
        assertThat(CatalogMcpTools.LIST_DESCRIPTION).contains("Ne pas utiliser");
    }
}
