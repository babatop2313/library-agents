package com.example.library.loan.adapter.in.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injecte dans un paramètre de contrôleur le {@code MemberId} du membre connecté (le {@code sub} du JWT).
 * Jamais un identifiant lu dans le corps ou dans l'URL (pas d'IDOR).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentMember {
}
