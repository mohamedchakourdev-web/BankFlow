package com.bankflow.outbox;

import com.bankflow.common.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferCompletedEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        UUID transferId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        Currency currency,
        Instant occurredAt
) {

    public static final String TYPE = "TransferCompleted";
    public static final int VERSION = 1;
}
