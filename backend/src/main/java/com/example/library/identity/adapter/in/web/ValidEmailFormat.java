package com.example.library.identity.adapter.in.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Format d'adresse e-mail valide. Valeur null, vide ou trop longue acceptée ici : voir @NotNull, @NotBlank
 * et @MaxTrimmedLength, pour ne produire qu'une seule erreur par champ.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = EmailFormatValidator.class)
@interface ValidEmailFormat {

    String message() default "L'adresse e-mail est invalide";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
