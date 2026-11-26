package com.example.library.catalog.api;

/** Résultat d'une restitution d'exemplaire ; {@code reason} (constante de {@link CatalogApi}) n'est renseignée que si {@code released} est faux. */
public record ReleaseResult(boolean released, String reason) {
}
