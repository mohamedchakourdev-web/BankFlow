package com.bankflow.audit;

import java.util.UUID;

public class MissingTransferException extends RuntimeException {

    public MissingTransferException(UUID eventId, UUID transferId) {
        super("Transfer " + transferId + " was not found for event " + eventId);
    }
}
