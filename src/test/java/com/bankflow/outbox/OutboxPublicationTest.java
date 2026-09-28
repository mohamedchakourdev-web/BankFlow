package com.bankflow.outbox;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.Currency;
import com.bankflow.transfer.dto.CreateTransferRequest;
import com.bankflow.transfer.dto.TransferResult;
import com.bankflow.transfer.service.TransferService;
import com.bankflow.user.Role;
import com.bankflow.user.User;
import com.bankflow.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "bankflow.outbox.publisher-enabled=true",
        "bankflow.outbox.poll-interval-ms=600000",
        "spring.kafka.listener.auto-startup=false"
})
@EmbeddedKafka(
        partitions = 1,
        topics = "bankflow.transfer-events",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class OutboxPublicationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private OutboxEventService outboxEventService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private ObjectMapper objectMapper;

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
    void publisherSendsTransferCompletedAndMarksOutboxPublished() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            User owner = userRepository.save(User.createCustomer(email(), "hash", "Publish", "Owner"));
            User receiver = userRepository.save(User.createCustomer(email(), "hash", "Publish", "Receiver"));
            Account source = accountRepository.save(Account.open(owner, accountNumber(), Currency.EUR));
            Account destination = accountRepository.save(Account.open(receiver, accountNumber(), Currency.EUR));
            source.credit(new BigDecimal("80.0000"));
            userId = owner.getId();
            receiverId = receiver.getId();
            sourceId = source.getId();
            destinationId = destination.getId();
        });

        TransferResult result = transferService.transfer(
                new AuthenticatedUser(userId, Role.CUSTOMER),
                "kafka-publish",
                new CreateTransferRequest(sourceId, destinationId, new BigDecimal("30.00"), "EUR")
        );
        UUID transferId = result.transfer().id();
        assertEquals(1, outboxEventRepository.countByAggregateId(transferId));

        outboxEventService.publishPending();

        OutboxEvent published = outboxEventRepository.findAll().stream()
                .filter(event -> transferId.equals(event.getAggregateId()))
                .findFirst()
                .orElseThrow();
        assertEquals(OutboxEventStatus.PUBLISHED, published.getStatus());
        assertNotNull(published.getPublishedAt());
        assertEquals(1, published.getAttempts());

        Map<String, Object> props = KafkaTestUtils.consumerProps("phase6-publication", "true", embeddedKafka);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "bankflow.transfer-events");
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(
                    consumer,
                    "bankflow.transfer-events",
                    Duration.ofSeconds(15)
            );
            assertEquals(transferId.toString(), record.key());
            JsonNode payload = objectMapper.readTree(record.value());
            assertEquals("TransferCompleted", payload.get("eventType").asText());
            assertEquals(1, payload.get("eventVersion").asInt());
            assertEquals(transferId.toString(), payload.get("transferId").asText());
            assertEquals(sourceId.toString(), payload.get("sourceAccountId").asText());
            assertEquals(destinationId.toString(), payload.get("destinationAccountId").asText());
            assertTrueAmount(record.value(), payload);
            assertEquals("EUR", payload.get("currency").asText());
            assertFalse(record.value().contains("password"));
            assertFalse(record.value().toLowerCase().contains("idempotency"));
        }
    }

    private void assertTrueAmount(String raw, JsonNode payload) {
        assertTrue(raw.contains("30.0000"));
        assertEquals(0, new BigDecimal(payload.get("amount").asText()).compareTo(new BigDecimal("30.0000")));
    }

    private String accountNumber() {
        return "9" + String.format("%011d", ThreadLocalRandom.current().nextLong(100_000_000_000L));
    }

    private String email() {
        return "outbox-publish-" + UUID.randomUUID() + "@example.com";
    }
}
