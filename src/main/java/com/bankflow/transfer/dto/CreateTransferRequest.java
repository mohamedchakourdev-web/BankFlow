package com.bankflow.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.bankflow.account.dto.SupportedCurrency;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

public record CreateTransferRequest(
        @Schema(example = "6f1c0a2e-1b2d-4c3e-8a11-0d5e6f708192")
        @NotNull(message = "Source account id is required")
        UUID sourceAccountId,
        @Schema(example = "8a2d4e6f-3c4d-4e5f-9b22-1e6f70819304")
        @NotNull(message = "Destination account id is required")
        UUID destinationAccountId,
        @Schema(example = "100.0000", description = "Positive decimal. Stored and returned at scale 4.")
        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than zero")
        @Digits(integer = 15, fraction = 4, message = "Amount must be a valid decimal")
        BigDecimal amount,
        @Schema(example = "EUR", allowableValues = {"USD", "EUR", "GBP"})
        @SupportedCurrency
        String currency
) {

    public CreateTransferRequest {
        if (currency != null) {
            String normalized = currency.trim().toUpperCase(Locale.ROOT);
            currency = normalized.isEmpty() ? null : normalized;
        }
    }
}
