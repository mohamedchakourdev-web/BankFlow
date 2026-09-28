package com.bankflow.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class IdempotentEventProcessor {

    static final String UNIQUE_CONSTRAINT = "processed_events_event_consumer_unique";

    private static final Logger log = LoggerFactory.getLogger(IdempotentEventProcessor.class);

    private final ProcessedEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;

    public IdempotentEventProcessor(
            ProcessedEventRepository processedEventRepository,
            TransactionTemplate transactionTemplate
    ) {
        this.processedEventRepository = processedEventRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public void process(UUID eventId, String consumerName, Runnable sideEffect) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (processedEventRepository.existsByEventIdAndConsumerName(eventId, consumerName)) {
                    log.info("Skipped duplicate event eventId={} consumer={}", eventId, consumerName);
                    return;
                }
                sideEffect.run();
                processedEventRepository.saveAndFlush(ProcessedEvent.start(eventId, consumerName));
            });
        } catch (DataIntegrityViolationException ex) {
            if (!isDuplicate(ex)) {
                throw ex;
            }
            log.info("Skipped duplicate event eventId={} consumer={}", eventId, consumerName);
        }
    }

    private boolean isDuplicate(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause().getMessage();
        return message != null && message.contains(UNIQUE_CONSTRAINT);
    }
}
