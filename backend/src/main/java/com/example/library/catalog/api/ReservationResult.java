package com.example.library.catalog.api;

/** Résultat d'une prise d'exemplaire ; {@code reason} (constante de {@link CatalogApi}) n'est renseignée que si {@code reserved} est faux. */
public record ReservationResult(boolean reserved, String reason) {
}
