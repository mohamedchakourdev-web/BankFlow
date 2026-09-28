package com.bankflow.transfer.dto;

import com.bankflow.common.Currency;
import com.bankflow.transfer.Transfer;
import com.bankflow.transfer.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        Currency currency,
        TransferStatus status,
        Instant createdAt
) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getSourceAccount().getId(),
                transfer.getDestinationAccount().getId(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getStatus(),
                transfer.getCreatedAt()
        );
    }
}
