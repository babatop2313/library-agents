package com.example.library.identity.adapter.in.web;

import com.example.library.identity.domain.model.Email;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

class EmailFormatValidator implements ConstraintValidator<ValidEmailFormat, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank() || Email.isTooLong(value)) {
            return true;   // déjà signalé par @NotNull, @NotBlank ou @MaxTrimmedLength
        }
        return Email.hasValidFormat(value);
    }
}
