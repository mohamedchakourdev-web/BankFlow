package com.bankflow.outbox;

import com.bankflow.transfer.Transfer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class OutboxEventService {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventService.class);
    private static final String AGGREGATE_TYPE = "Transfer";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaSettings kafkaSettings;
    private final int batchSize;

    public OutboxEventService(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafkaTemplate,
            KafkaSettings kafkaSettings,
            @Value("${bankflow.outbox.batch-size:50}") int batchSize
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaSettings = kafkaSettings;
        this.batchSize = batchSize;
    }

    public OutboxEvent enqueue(Transfer transfer) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = transfer.getCreatedAt() == null ? Instant.now() : transfer.getCreatedAt();
        TransferCompletedEvent event = new TransferCompletedEvent(
                eventId,
                TransferCompletedEvent.TYPE,
                TransferCompletedEvent.VERSION,
                transfer.getId(),
                transfer.getSourceAccount().getId(),
                transfer.getDestinationAccount().getId(),
                transfer.getAmount(),
                transfer.getCurrency(),
                occurredAt
        );
        try {
            String payload = objectMapper.writeValueAsString(event);
            return outboxEventRepository.saveAndFlush(OutboxEvent.pending(
                    eventId,
                    AGGREGATE_TYPE,
                    transfer.getId(),
                    TransferCompletedEvent.TYPE,
                    payload
            ));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize transfer event", ex);
        }
    }

    @Transactional
    public void publishPending() {
        for (OutboxEvent event : outboxEventRepository.lockNextPending(batchSize)) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            kafkaTemplate.send(kafkaSettings.getTransferTopic(), event.getAggregateId().toString(), event.getPayload())
                    .get(5, TimeUnit.SECONDS);
            event.markPublished();
            log.info(
                    "Published outbox event eventId={} transferId={} eventType={} attempt={}",
                    event.getId(),
                    event.getAggregateId(),
                    event.getEventType(),
                    event.getAttempts()
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            event.recordFailure(ex.getMessage());
            log.warn(
                    "Outbox publish interrupted eventId={} transferId={} attempt={}",
                    event.getId(),
                    event.getAggregateId(),
                    event.getAttempts()
            );
        } catch (ExecutionException | TimeoutException | RuntimeException ex) {
            event.recordFailure(rootMessage(ex));
            log.warn(
                    "Outbox publish failed eventId={} transferId={} attempt={} reason={}",
                    event.getId(),
                    event.getAggregateId(),
                    event.getAttempts(),
                    event.getLastError()
            );
        }
    }

    private String rootMessage(Throwable error) {
        String best = null;
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                best = current.getMessage();
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return best == null ? error.getClass().getSimpleName() : best;
    }
}
