package com.bankflow.notification;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.bankflow.audit.AuditActions;
import com.bankflow.audit.AuditLogRepository;
import com.bankflow.audit.AuditWriteProbe;
import com.bankflow.audit.TransferAuditConsumer;
import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.Currency;
import com.bankflow.event.ProcessedEventRepository;
import com.bankflow.outbox.TransferCompletedEvent;
import com.bankflow.transfer.dto.CreateTransferRequest;
import com.bankflow.transfer.service.TransferService;
import com.bankflow.user.Role;
import com.bankflow.user.User;
import com.bankflow.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@SpringBootTest
class Phase7ConsumerTest {

    @Autowired
    private TransferAuditConsumer auditConsumer;

    @Autowired
    private TransferNotificationConsumer notificationConsumer;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoSpyBean
    private AuditWriteProbe auditWriteProbe;

    private UUID userId;
    private UUID receiverId;
    private UUID sourceId;
    private UUID destinationId;

    @AfterEach
    void cleanup() {
        reset(auditWriteProbe);
        if (userId == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("delete from processed_events where consumer_name in ('audit', 'notification')");
            jdbcTemplate.update("""
                    delete from notifications
                    where user_id in (?, ?)
                    """, userId, receiverId);
            jdbcTemplate.update("""
                    delete from audit_logs
                    where user_id in (?, ?) or entity_id in (
                        select id from transfers
                        where source_account_id in (?, ?) or destination_account_id in (?, ?)
                    )
                    """, userId, receiverId, sourceId, destinationId, sourceId, destinationId);
            jdbcTemplate.update("delete from idempotency_keys where user_id in (?, ?)", userId, receiverId);
            jdbcTemplate.update("""
                    delete from outbox_events
                    where aggregate_id in (
                        select id from transfers
                        where source_account_id in (?, ?) or destination_account_id in (?, ?)
                    )
                    """, sourceId, destinationId, sourceId, destinationId);
            jdbcTemplate.update(
                    "delete from transfers where source_account_id in (?, ?) or destination_account_id in (?, ?)",
                    sourceId, destinationId, sourceId, destinationId
            );
            jdbcTemplate.update("delete from accounts where id in (?, ?)", sourceId, destinationId);
            jdbcTemplate.update("delete from users where id in (?, ?)", userId, receiverId);
        });
    }

    @Test
    void duplicateEventCreatesOneAuditAndOneNotificationPerParty() throws Exception {
        UUID transferId = committedTransfer("90.0000", "20.00");
        UUID eventId = UUID.randomUUID();
        String payload = payload(transferId, eventId);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        auditConsumer.onTransferCompleted(payload, acknowledgment);
        notificationConsumer.onTransferCompleted(payload, acknowledgment);
        auditConsumer.onTransferCompleted(payload, acknowledgment);
        notificationConsumer.onTransferCompleted(payload, acknowledgment);

        assertEquals(1, auditLogRepository.countByActionAndEntityId(AuditActions.TRANSFER_COMPLETED, transferId));
        assertEquals(1, notificationRepository.countByUser_IdAndTypeAndRelatedEntityId(userId, NotificationType.TRANSFER_SENT, transferId));
        assertEquals(1, notificationRepository.countByUser_IdAndTypeAndRelatedEntityId(receiverId, NotificationType.TRANSFER_RECEIVED, transferId));
        assertEquals(2, processedEventRepository.countByEventId(eventId));
        String metadata = auditLogRepository.findAll().stream()
                .filter(log -> transferId.equals(log.getEntityId()))
                .findFirst()
                .orElseThrow()
                .getMetadata();
        com.fasterxml.jackson.databind.JsonNode metadataJson = objectMapper.readTree(metadata);
        assertTrue(metadataJson.get("amount").isTextual());
        assertEquals(0, new BigDecimal(metadataJson.get("amount").asText()).compareTo(new BigDecimal("20.0000")));
        assertEquals("EUR", metadataJson.get("currency").asText());
        assertEquals(0, accountRepository.findById(sourceId).orElseThrow().getBalance().compareTo(new BigDecimal("70.0000")));
        verify(acknowledgment, org.mockito.Mockito.atLeastOnce()).acknowledge();
    }

    @Test
    void databaseFailureDoesNotCommitProcessedEventAndRetrySucceeds() throws Exception {
        UUID transferId = committedTransfer("40.0000", "10.00");
        String payload = payload(transferId, UUID.randomUUID());
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        doThrow(new IllegalStateException("database unavailable")).when(auditWriteProbe).afterWrite();

        assertThrows(IllegalStateException.class,
                () -> auditConsumer.onTransferCompleted(payload, acknowledgment));
        verify(acknowledgment, never()).acknowledge();
        assertEquals(0, auditLogRepository.countByActionAndEntityId(AuditActions.TRANSFER_COMPLETED, transferId));
        assertEquals(0, processedEventRepository.countByEventId(UUID.fromString(objectMapper.readTree(payload).get("eventId").asText())));

        reset(auditWriteProbe);
        auditConsumer.onTransferCompleted(payload, acknowledgment);
        verify(acknowledgment).acknowledge();
        assertEquals(1, auditLogRepository.countByActionAndEntityId(AuditActions.TRANSFER_COMPLETED, transferId));
        assertEquals(1, processedEventRepository.countByEventId(UUID.fromString(objectMapper.readTree(payload).get("eventId").asText())));
        assertEquals(0, accountRepository.findById(sourceId).orElseThrow().getBalance().compareTo(new BigDecimal("30.0000")));
    }

    @Test
    void malformedEventIsAcknowledgedWithoutASideEffect() {
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        auditConsumer.onTransferCompleted("{", acknowledgment);
        notificationConsumer.onTransferCompleted("{", acknowledgment);
        verify(acknowledgment, org.mockito.Mockito.times(2)).acknowledge();
    }

    private UUID committedTransfer(String opening, String amount) {
        transactionTemplate.executeWithoutResult(status -> {
            User owner = userRepository.save(User.createCustomer(email(), "hash", "Consumer", "Owner"));
            User receiver = userRepository.save(User.createCustomer(email(), "hash", "Consumer", "Receiver"));
            Account source = accountRepository.save(Account.open(owner, accountNumber(), Currency.EUR));
            Account destination = accountRepository.save(Account.open(receiver, accountNumber(), Currency.EUR));
            source.credit(new BigDecimal(opening));
            userId = owner.getId();
            receiverId = receiver.getId();
            sourceId = source.getId();
            destinationId = destination.getId();
        });
        return transferService.transfer(
                new AuthenticatedUser(userId, Role.CUSTOMER),
                "phase7-" + UUID.randomUUID(),
                new CreateTransferRequest(sourceId, destinationId, new BigDecimal(amount), "EUR")
        ).transfer().id();
    }

    private String payload(UUID transferId, UUID eventId) throws Exception {
        return objectMapper.writeValueAsString(new TransferCompletedEvent(
                eventId,
                TransferCompletedEvent.TYPE,
                TransferCompletedEvent.VERSION,
                transferId,
                sourceId,
                destinationId,
                new BigDecimal("999.0000"),
                Currency.EUR,
                Instant.parse("2026-09-28T00:00:00Z")
        ));
    }

    private String accountNumber() {
        return "4" + String.format("%011d", ThreadLocalRandom.current().nextLong(100_000_000_000L));
    }

    private String email() {
        return "phase7-consumer-" + UUID.randomUUID() + "@example.com";
    }
}
