package com.example.library.catalog.adapter.in.web;

import com.example.library.catalog.domain.model.Book;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

class MaxTrimmedLengthValidator implements ConstraintValidator<MaxTrimmedLength, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.strip().length() <= Book.TEXT_MAX_LENGTH;
    }
}
