package com.bankflow.event;

import com.bankflow.outbox.TransferCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class TransferEventDecoder {

    private final ObjectMapper objectMapper;

    public TransferEventDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DecodeResult decode(String payload) {
        TransferCompletedEvent event;
        try {
            event = objectMapper.readValue(payload, TransferCompletedEvent.class);
        } catch (Exception ex) {
            return DecodeResult.invalid("Transfer event payload is not valid JSON");
        }
        String problem = problem(event);
        if (problem != null) {
            return DecodeResult.invalid(problem);
        }
        return DecodeResult.valid(event);
    }

    private String problem(TransferCompletedEvent event) {
        if (event == null) {
            return "Transfer event payload is empty";
        }
        if (event.eventId() == null) {
            return "eventId is required";
        }
        if (!TransferCompletedEvent.TYPE.equals(event.eventType())) {
            return "Unexpected event type";
        }
        if (event.eventVersion() != TransferCompletedEvent.VERSION) {
            return "Unsupported event version " + event.eventVersion();
        }
        if (event.transferId() == null || event.sourceAccountId() == null || event.destinationAccountId() == null) {
            return "Transfer event is missing an account or transfer id";
        }
        if (event.amount() == null || event.currency() == null || event.occurredAt() == null) {
            return "Transfer event is missing amount, currency, or occurredAt";
        }
        if (event.amount().signum() <= 0) {
            return "Transfer event amount must be positive";
        }
        return null;
    }

    public record DecodeResult(TransferCompletedEvent event, String problem) {

        static DecodeResult valid(TransferCompletedEvent event) {
            return new DecodeResult(event, null);
        }

        static DecodeResult invalid(String problem) {
            return new DecodeResult(null, problem);
        }

        public boolean valid() {
            return problem == null;
        }
    }
}
