package com.bankflow.transaction.dto;

import com.bankflow.common.Currency;
import com.bankflow.transfer.Transfer;
import com.bankflow.transfer.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        TransactionRecordType type,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        Currency currency,
        TransferStatus status,
        Instant createdAt
) {

    public static TransactionResponse from(Transfer transfer) {
        return new TransactionResponse(
                transfer.getId(),
                TransactionRecordType.TRANSFER,
                transfer.getSourceAccount().getId(),
                transfer.getDestinationAccount().getId(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getStatus(),
                transfer.getCreatedAt()
        );
    }
}
