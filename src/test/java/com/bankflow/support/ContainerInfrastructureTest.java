package com.bankflow.support;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.Currency;
import com.bankflow.outbox.OutboxEvent;
import com.bankflow.outbox.OutboxEventRepository;
import com.bankflow.outbox.OutboxEventService;
import com.bankflow.outbox.OutboxEventStatus;
import com.bankflow.transfer.dto.CreateTransferRequest;
import com.bankflow.transfer.dto.TransferResult;
import com.bankflow.transfer.service.TransferService;
import com.bankflow.user.Role;
import com.bankflow.user.User;
import com.bankflow.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "bankflow.outbox.publisher-enabled=true",
        "bankflow.outbox.poll-interval-ms=600000",
        "spring.kafka.listener.auto-startup=false"
})
class ContainerInfrastructureTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @org.springframework.test.context.DynamicPropertySource
    static void kafka(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

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
    void freshPostgresAndKafkaAcceptATransferAndPublishTheOutbox() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            User owner = userRepository.save(User.createCustomer(email(), "hash", "Container", "Owner"));
            User receiver = userRepository.save(User.createCustomer(email(), "hash", "Container", "Receiver"));
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
                "container-transfer",
                new CreateTransferRequest(sourceId, destinationId, new BigDecimal("30.00"), "EUR")
        );
        UUID transferId = result.transfer().id();
        outboxEventService.publishPending();

        OutboxEvent published = outboxEventRepository.findAll().stream()
                .filter(event -> transferId.equals(event.getAggregateId()))
                .findFirst()
                .orElseThrow();
        assertEquals(OutboxEventStatus.PUBLISHED, published.getStatus());
        assertNotNull(published.getPublishedAt());

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "container-infrastructure");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("bankflow.transfer-events"));
            ConsumerRecord<String, String> record = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (record == null && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> candidate : polled) {
                    if (transferId.toString().equals(candidate.key())) {
                        record = candidate;
                        break;
                    }
                }
            }
            assertNotNull(record);
            JsonNode payload = objectMapper.readTree(record.value());
            assertEquals("TransferCompleted", payload.get("eventType").asText());
            assertEquals(1, payload.get("eventVersion").asInt());
            assertTrue(record.value().contains("30.0000"));
            assertFalse(record.value().toLowerCase().contains("password"));
        }
    }

    private String accountNumber() {
        return "8" + String.format("%011d", ThreadLocalRandom.current().nextLong(100_000_000_000L));
    }

    private String email() {
        return "container-" + UUID.randomUUID() + "@example.com";
    }
}
