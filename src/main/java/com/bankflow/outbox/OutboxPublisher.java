package com.bankflow.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "bankflow.outbox", name = "publisher-enabled", havingValue = "true")
public class OutboxPublisher {

    private final OutboxEventService outboxEventService;

    public OutboxPublisher(OutboxEventService outboxEventService) {
        this.outboxEventService = outboxEventService;
    }

    @Scheduled(fixedDelayString = "${bankflow.outbox.poll-interval-ms:2000}")
    public void publishPending() {
        outboxEventService.publishPending();
    }
}
