package com.bankflow.audit;

import com.bankflow.account.Account;
import com.bankflow.event.IdempotentEventProcessor;
import com.bankflow.outbox.TransferCompletedEvent;
import com.bankflow.transfer.Transfer;
import com.bankflow.transfer.repository.TransferRepository;
import com.bankflow.user.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final TransferRepository transferRepository;
    private final IdempotentEventProcessor idempotentEventProcessor;
    private final ObjectMapper objectMapper;
    private final AuditWriteProbe auditWriteProbe;

    public AuditService(
            AuditLogRepository auditLogRepository,
            TransferRepository transferRepository,
            IdempotentEventProcessor idempotentEventProcessor,
            ObjectMapper objectMapper,
            AuditWriteProbe auditWriteProbe
    ) {
        this.auditLogRepository = auditLogRepository;
        this.transferRepository = transferRepository;
        this.idempotentEventProcessor = idempotentEventProcessor;
        this.objectMapper = objectMapper;
        this.auditWriteProbe = auditWriteProbe;
    }

    @Transactional
    public void userRegistered(User user) {
        save(user, AuditActions.USER_REGISTERED, AuditActions.USER, user.getId(), json(Map.of("role", user.getRole().name())));
    }

    @Transactional
    public void loginSucceeded(User user) {
        save(user, AuditActions.USER_LOGIN_SUCCESS, AuditActions.USER, user.getId(), json(Map.of("role", user.getRole().name())));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loginFailed() {
        save(null, AuditActions.USER_LOGIN_FAILED, AuditActions.AUTHENTICATION, UUID.randomUUID(), json(Map.of("outcome", "failure")));
    }

    @Transactional
    public void accountCreated(User owner, Account account) {
        save(owner, AuditActions.ACCOUNT_CREATED, AuditActions.ACCOUNT, account.getId(), json(Map.of(
                "currency", account.getCurrency().name()
        )));
    }

    public void transferCompleted(TransferCompletedEvent event) {
        idempotentEventProcessor.process(event.eventId(), AuditActions.CONSUMER, () -> {
            Transfer transfer = transferRepository.findById(event.transferId())
                    .orElseThrow(() -> new MissingTransferException(event.eventId(), event.transferId()));
            Account source = transfer.getSourceAccount();
            Account destination = transfer.getDestinationAccount();
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("sourceAccountId", source.getId().toString());
            metadata.put("destinationAccountId", destination.getId().toString());
            metadata.put("amount", transfer.getAmount().toPlainString());
            metadata.put("currency", transfer.getCurrency().name());
            save(source.getUser(), AuditActions.TRANSFER_COMPLETED, AuditActions.TRANSFER, transfer.getId(), json(metadata));
        });
    }

    private void save(User user, String action, String entityType, UUID entityId, String metadata) {
        auditLogRepository.saveAndFlush(AuditLog.record(user, action, entityType, entityId, metadata));
        auditWriteProbe.afterWrite();
    }

    private String json(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize audit metadata", ex);
        }
    }
}
