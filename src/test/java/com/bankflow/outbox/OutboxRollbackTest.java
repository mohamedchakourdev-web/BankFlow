package com.bankflow.outbox;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.Currency;
import com.bankflow.idempotency.IdempotencyKeyRepository;
import com.bankflow.transfer.CapturingStatementInspector;
import com.bankflow.transfer.dto.CreateTransferRequest;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.bankflow.transfer.CapturingStatementInspector")
class OutboxRollbackTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private OutboxInsertProbe outboxInsertProbe;

    private UUID userId;
    private UUID receiverId;
    private UUID sourceId;
    private UUID destinationId;

    @AfterEach
    void cleanup() {
        reset(outboxInsertProbe);
        CapturingStatementInspector.SQL.clear();
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
    void failureAfterOutboxInsertRollsBackMoneyTransferAndEvent() {
        transactionTemplate.executeWithoutResult(status -> {
            User owner = userRepository.save(User.createCustomer(email(), "hash", "Outbox", "Owner"));
            User receiver = userRepository.save(User.createCustomer(email(), "hash", "Outbox", "Receiver"));
            Account source = accountRepository.save(Account.open(owner, accountNumber(), Currency.EUR));
            Account destination = accountRepository.save(Account.open(receiver, accountNumber(), Currency.EUR));
            source.credit(new BigDecimal("100.0000"));
            destination.credit(new BigDecimal("20.0000"));
            userId = owner.getId();
            receiverId = receiver.getId();
            sourceId = source.getId();
            destinationId = destination.getId();
        });

        int outboxBefore = jdbcTemplate.queryForObject("select count(*) from outbox_events", Integer.class);
        CapturingStatementInspector.SQL.clear();
        doThrow(new IllegalStateException("forced failure after outbox insert")).when(outboxInsertProbe).afterInsert();

        AuthenticatedUser owner = new AuthenticatedUser(userId, Role.CUSTOMER);
        CreateTransferRequest request = new CreateTransferRequest(sourceId, destinationId, new BigDecimal("35.00"), "EUR");
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> transferService.transfer(owner, "outbox-rollback", request)
        );
        assertEquals("forced failure after outbox insert", failure.getMessage());

        List<String> sql = List.copyOf(CapturingStatementInspector.SQL);
        assertTrue(sql.stream().anyMatch(statement -> statement.toLowerCase().contains("update accounts")),
                "expected the debit to reach PostgreSQL, saw: " + sql);
        assertTrue(sql.stream().anyMatch(statement -> statement.toLowerCase().contains("outbox_events")),
                "expected the outbox insert to reach PostgreSQL, saw: " + sql);

        Account source = accountRepository.findById(sourceId).orElseThrow();
        Account destination = accountRepository.findById(destinationId).orElseThrow();
        assertEquals(0, source.getBalance().compareTo(new BigDecimal("100.0000")));
        assertEquals(0, destination.getBalance().compareTo(new BigDecimal("20.0000")));
        assertEquals(0, transferRepository.countBySourceAccount_Id(sourceId));
        assertEquals(0, idempotencyKeyRepository.countByUser_IdAndIdempotencyKey(userId, "outbox-rollback"));
        assertEquals(outboxBefore, jdbcTemplate.queryForObject("select count(*) from outbox_events", Integer.class));
    }

    private String accountNumber() {
        return "7" + String.format("%011d", ThreadLocalRandom.current().nextLong(100_000_000_000L));
    }

    private String email() {
        return "outbox-rollback-" + UUID.randomUUID() + "@example.com";
    }
}
