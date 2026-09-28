package com.bankflow.auth.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordPolicyValidator implements ConstraintValidator<PasswordPolicy, String> {

    static final int MIN_LENGTH = 8;
    static final int MAX_LENGTH = 72;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if (value.length() < MIN_LENGTH) {
            return reject(context, "Password must be at least 8 characters");
        }
        if (value.length() > MAX_LENGTH) {
            return reject(context, "Password must be at most 72 characters");
        }
        if (value.chars().noneMatch(Character::isLetter)) {
            return reject(context, "Password must contain at least one letter");
        }
        if (value.chars().noneMatch(Character::isDigit)) {
            return reject(context, "Password must contain at least one number");
        }
        return true;
    }

    private boolean reject(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
