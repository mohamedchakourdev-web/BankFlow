package com.bankflow.account.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import com.bankflow.common.Currency;

public class SupportedCurrencyValidator implements ConstraintValidator<SupportedCurrency, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            Currency.valueOf(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
