package com.bankflow.notification;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.bankflow.audit.AuditActions;
import com.bankflow.audit.AuditLogRepository;
import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.Currency;
import com.bankflow.event.ProcessedEventRepository;
import com.bankflow.outbox.KafkaSettings;
import com.bankflow.outbox.OutboxEvent;
import com.bankflow.outbox.OutboxEventRepository;
import com.bankflow.outbox.OutboxEventStatus;
import com.bankflow.transfer.dto.CreateTransferRequest;
import com.bankflow.transfer.service.TransferService;
import com.bankflow.user.Role;
import com.bankflow.user.User;
import com.bankflow.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(properties = {
        "bankflow.outbox.publisher-enabled=true",
        "bankflow.outbox.poll-interval-ms=200",
        "spring.kafka.listener.auto-startup=true"
})
@EmbeddedKafka(
        partitions = 1,
        topics = "bankflow.transfer-events",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Phase7KafkaTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaSettings kafkaSettings;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID userId;
    private UUID receiverId;
    private UUID sourceId;
    private UUID destinationId;

    @AfterEach
    void cleanup() {
        if (userId == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("delete from notifications where user_id in (?, ?)", userId, receiverId);
            jdbcTemplate.update("delete from audit_logs where user_id in (?, ?)", userId, receiverId);
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
    void publishedTransferIsAuditedAndNotifiedOnceWhenRedelivered() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            User owner = userRepository.save(User.createCustomer(email(), "hash", "Kafka", "Owner"));
            User receiver = userRepository.save(User.createCustomer(email(), "hash", "Kafka", "Receiver"));
            Account source = accountRepository.save(Account.open(owner, accountNumber(), Currency.EUR));
            Account destination = accountRepository.save(Account.open(receiver, accountNumber(), Currency.EUR));
            source.credit(new BigDecimal("60.0000"));
            userId = owner.getId();
            receiverId = receiver.getId();
            sourceId = source.getId();
            destinationId = destination.getId();
        });
        UUID transferId = transferService.transfer(
                new AuthenticatedUser(userId, Role.CUSTOMER),
                "phase7-kafka",
                new CreateTransferRequest(sourceId, destinationId, new BigDecimal("12.00"), "EUR")
        ).transfer().id();

        waitUntil(() -> auditLogRepository.countByActionAndEntityId(AuditActions.TRANSFER_COMPLETED, transferId) == 1
                && notificationRepository.countByUser_IdAndTypeAndRelatedEntityId(userId, NotificationType.TRANSFER_SENT, transferId) == 1
                && notificationRepository.countByUser_IdAndTypeAndRelatedEntityId(receiverId, NotificationType.TRANSFER_RECEIVED, transferId) == 1);

        OutboxEvent published = outboxEventRepository.findAll().stream()
                .filter(event -> transferId.equals(event.getAggregateId()))
                .findFirst()
                .orElseThrow();
        assertEquals(OutboxEventStatus.PUBLISHED, published.getStatus());
        JsonNode payload = objectMapper.readTree(published.getPayload());
        assertFalse(published.getPayload().contains("password"));
        kafkaTemplate.send(kafkaSettings.getTransferTopic(), transferId.toString(), published.getPayload()).get();

        Thread.sleep(1500);
        assertEquals(1, auditLogRepository.countByActionAndEntityId(AuditActions.TRANSFER_COMPLETED, transferId));
        assertEquals(1, notificationRepository.countByUser_IdAndTypeAndRelatedEntityId(userId, NotificationType.TRANSFER_SENT, transferId));
        assertEquals(1, notificationRepository.countByUser_IdAndTypeAndRelatedEntityId(receiverId, NotificationType.TRANSFER_RECEIVED, transferId));
        assertEquals(2, processedEventRepository.countByEventId(UUID.fromString(payload.get("eventId").asText())));
        assertEquals(0, accountRepository.findById(sourceId).orElseThrow().getBalance().compareTo(new BigDecimal("48.0000")));
    }

    private void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for transfer consumers");
    }

    private String accountNumber() {
        return "5" + String.format("%011d", ThreadLocalRandom.current().nextLong(100_000_000_000L));
    }

    private String email() {
        return "phase7-kafka-" + UUID.randomUUID() + "@example.com";
    }
}
