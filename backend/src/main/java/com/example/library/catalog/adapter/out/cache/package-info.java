/**
 * Adapter sortant Redis : cache de la liste des livres disponibles, en décorateur de l'adapter de persistance.
 *
 * <p>RC-17 : tout futur port de sortie qui modifie {@code availableCopies} ou ajoute un livre (emprunt, retour,
 * ajout d'exemplaires du module {@code loan}) doit être décoré ici, avec invalidation de la clé après commit.
 * Le cache n'est jamais la source de vérité.
 */
package com.example.library.catalog.adapter.out.cache;
