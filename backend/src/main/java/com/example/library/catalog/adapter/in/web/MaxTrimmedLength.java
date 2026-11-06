package com.example.library.catalog.adapter.in.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Longueur maximale mesurée après suppression des espaces de début et de fin. Valeur null acceptée. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = MaxTrimmedLengthValidator.class)
@interface MaxTrimmedLength {

    String message() default "Le texte est trop long";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
