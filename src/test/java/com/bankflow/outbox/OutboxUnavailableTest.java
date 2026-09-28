package com.bankflow.outbox;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.Currency;
import com.bankflow.transfer.dto.CreateTransferRequest;
import com.bankflow.transfer.dto.TransferResult;
import com.bankflow.transfer.repository.TransferRepository;
import com.bankflow.transfer.service.TransferService;
import com.bankflow.user.Role;
import com.bankflow.user.User;
import com.bankflow.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "bankflow.outbox.publisher-enabled=true",
        "bankflow.outbox.poll-interval-ms=600000",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.properties.request.timeout.ms=1000",
        "spring.kafka.properties.default.api.timeout.ms=2000",
        "spring.kafka.producer.properties.max.block.ms=1000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=2000"
})
class OutboxUnavailableTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private OutboxEventService outboxEventService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
            jdbcTemplate.update("delete from notifications where user_id in (?, ?)", userId, receiverId);
            jdbcTemplate.update("delete from audit_logs where user_id in (?, ?)", userId, receiverId);
            jdbcTemplate.update("delete from users where id in (?, ?)", userId, receiverId);
        });
    }

    @Test
    void transferCommitsWhenKafkaIsUnavailableAndOutboxStaysPending() {
        transactionTemplate.executeWithoutResult(status -> {
            User owner = userRepository.save(User.createCustomer(email(), "hash", "Down", "Owner"));
            User receiver = userRepository.save(User.createCustomer(email(), "hash", "Down", "Receiver"));
            Account source = accountRepository.save(Account.open(owner, accountNumber(), Currency.EUR));
            Account destination = accountRepository.save(Account.open(receiver, accountNumber(), Currency.EUR));
            source.credit(new BigDecimal("100.0000"));
            userId = owner.getId();
            receiverId = receiver.getId();
            sourceId = source.getId();
            destinationId = destination.getId();
        });

        TransferResult result = transferService.transfer(
                new AuthenticatedUser(userId, Role.CUSTOMER),
                "kafka-down",
                new CreateTransferRequest(sourceId, destinationId, new BigDecimal("25.00"), "EUR")
        );

        UUID transferId = result.transfer().id();
        OutboxEvent pending = outboxEventRepository.findAll().stream()
                .filter(event -> transferId.equals(event.getAggregateId()))
                .findFirst()
                .orElseThrow();
        assertEquals(OutboxEventStatus.PENDING, pending.getStatus());
        assertEquals(0, pending.getAttempts());

        outboxEventService.publishPending();

        OutboxEvent afterFailure = outboxEventRepository.findById(pending.getId()).orElseThrow();
        assertEquals(OutboxEventStatus.PENDING, afterFailure.getStatus());
        assertTrue(afterFailure.getAttempts() >= 1);
        assertNotNull(afterFailure.getLastError());
        assertTrue(!afterFailure.getLastError().isBlank());
        assertEquals(1, transferRepository.countBySourceAccount_Id(sourceId));
        assertEquals(0, accountRepository.findById(sourceId).orElseThrow().getBalance().compareTo(new BigDecimal("75.0000")));
        assertEquals(0, accountRepository.findById(destinationId).orElseThrow().getBalance().compareTo(new BigDecimal("25.0000")));
    }

    private String accountNumber() {
        return "8" + String.format("%011d", ThreadLocalRandom.current().nextLong(100_000_000_000L));
    }

    private String email() {
        return "outbox-down-" + UUID.randomUUID() + "@example.com";
    }
}
