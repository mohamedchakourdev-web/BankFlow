package com.bankflow.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.Locale;

public record CreateAccountRequest(
        @Schema(example = "EUR", allowableValues = {"USD", "EUR", "GBP"})
        @NotBlank(message = "Currency is required")
        @SupportedCurrency
        String currency
) {

    public CreateAccountRequest {
        currency = currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
    }
}
