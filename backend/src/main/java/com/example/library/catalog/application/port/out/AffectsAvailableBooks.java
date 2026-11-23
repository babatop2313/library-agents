package com.example.library.catalog.application.port.out;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marque un port de sortie d'écriture qui modifie {@code availableCopies} d'un livre ou l'ensemble des livres
 * disponibles (création, suppression, emprunt, retour…). Toute interface ainsi annotée doit être implémentée par
 * le décorateur de cache, qui invalide la clé après le commit (RM-CAT-42, vérifié par CacheArchitectureTest).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AffectsAvailableBooks {
}
