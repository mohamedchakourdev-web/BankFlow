package com.bankflow.notification;

import com.bankflow.audit.MissingTransferException;
import com.bankflow.event.TransferEventDecoder;
import com.bankflow.outbox.TransferCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class TransferNotificationConsumer {

    public static final String GROUP = "${bankflow.kafka.notification-group:bankflow-notification-consumer}";

    private static final Logger log = LoggerFactory.getLogger(TransferNotificationConsumer.class);

    private final TransferEventDecoder decoder;
    private final NotificationService notificationService;

    public TransferNotificationConsumer(TransferEventDecoder decoder, NotificationService notificationService) {
        this.decoder = decoder;
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "#{@kafkaSettings.transferTopic}", groupId = GROUP)
    public void onTransferCompleted(String payload, Acknowledgment acknowledgment) {
        TransferEventDecoder.DecodeResult decoded = decoder.decode(payload);
        if (!decoded.valid()) {
            log.error("Discarded invalid transfer event for notification: {}", decoded.problem());
            acknowledgment.acknowledge();
            return;
        }
        TransferCompletedEvent event = decoded.event();
        log.info("Notifying transfer event eventId={} transferId={} eventVersion={}",
                event.eventId(), event.transferId(), event.eventVersion());
        try {
            notificationService.transferCompleted(event);
        } catch (MissingTransferException ex) {
            log.error("Discarded notification event because the transfer is missing eventId={} transferId={}",
                    event.eventId(), event.transferId());
            acknowledgment.acknowledge();
            return;
        }
        acknowledgment.acknowledge();
        log.info("Notified transfer event eventId={} transferId={}", event.eventId(), event.transferId());
    }
}
