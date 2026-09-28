package com.bankflow.audit;

import com.bankflow.event.TransferEventDecoder;
import com.bankflow.outbox.TransferCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class TransferAuditConsumer {

    public static final String GROUP = "${bankflow.kafka.audit-group:bankflow-audit-consumer}";

    private static final Logger log = LoggerFactory.getLogger(TransferAuditConsumer.class);

    private final TransferEventDecoder decoder;
    private final AuditService auditService;

    public TransferAuditConsumer(TransferEventDecoder decoder, AuditService auditService) {
        this.decoder = decoder;
        this.auditService = auditService;
    }

    @KafkaListener(topics = "#{@kafkaSettings.transferTopic}", groupId = GROUP)
    public void onTransferCompleted(String payload, Acknowledgment acknowledgment) {
        TransferEventDecoder.DecodeResult decoded = decoder.decode(payload);
        if (!decoded.valid()) {
            log.error("Discarded invalid transfer event for audit: {}", decoded.problem());
            acknowledgment.acknowledge();
            return;
        }
        TransferCompletedEvent event = decoded.event();
        log.info("Auditing transfer event eventId={} transferId={} eventVersion={}",
                event.eventId(), event.transferId(), event.eventVersion());
        try {
            auditService.transferCompleted(event);
        } catch (MissingTransferException ex) {
            log.error("Discarded audit event because the transfer is missing eventId={} transferId={}",
                    event.eventId(), event.transferId());
            acknowledgment.acknowledge();
            return;
        }
        acknowledgment.acknowledge();
        log.info("Audited transfer event eventId={} transferId={}", event.eventId(), event.transferId());
    }
}
