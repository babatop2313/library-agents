package com.example.library.catalog.adapter.in.web;

import com.example.library.catalog.domain.model.Isbn;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Délègue au domaine : la règle de forme de l'ISBN n'est pas dupliquée ici. */
class IsbnFormatValidator implements ConstraintValidator<ValidIsbnFormat, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || Isbn.isValid(value);
    }
}
