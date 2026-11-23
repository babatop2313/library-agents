package com.example.library.identity.adapter.in.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

class MaxTrimmedLengthValidator implements ConstraintValidator<MaxTrimmedLength, String> {

    private int max;

    @Override
    public void initialize(MaxTrimmedLength constraint) {
        this.max = constraint.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.strip().length() <= max;
    }
}
