package com.example.library.catalog.adapter.in.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.example.library.catalog.domain.model.Book;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Longueur maximale mesurée après suppression des espaces de début et de fin. Valeur null acceptée. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = MaxTrimmedLengthValidator.class)
@interface MaxTrimmedLength {

    String message() default "Le texte est trop long";

    /** Longueur maximale après trim ; 255 par défaut (titre et auteur). */
    int max() default Book.TEXT_MAX_LENGTH;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
