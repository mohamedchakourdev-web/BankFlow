package com.bankflow.account.dto;

import com.bankflow.account.Account;
import com.bankflow.common.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String accountNumber,
        BigDecimal balance,
        Currency currency,
        Instant createdAt,
        Instant updatedAt
) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getBalance(),
                account.getCurrency(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
