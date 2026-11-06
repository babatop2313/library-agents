package com.example.library.catalog.adapter.in.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** L'ISBN doit comporter 13 chiffres (tirets et espaces tolérés). Valeur null acceptée : voir @NotNull. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = IsbnFormatValidator.class)
@interface ValidIsbnFormat {

    String message() default "L'ISBN doit comporter 13 chiffres";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
