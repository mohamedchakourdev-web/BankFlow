package com.bankflow.transfer;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.Currency;
import com.bankflow.common.exception.BankFlowException;
import com.bankflow.idempotency.IdempotencyKeyRepository;
import com.bankflow.transfer.dto.CreateTransferRequest;
import com.bankflow.transfer.dto.TransferResult;
import com.bankflow.transfer.repository.TransferRepository;
import com.bankflow.transfer.service.TransferService;
import com.bankflow.user.Role;
import com.bankflow.user.User;
import com.bankflow.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TransferConcurrencyTest {

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

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void onlyOneOfTwoConcurrentDebitsCanSpendTheSameBalance() throws Exception {
        Fixture fixture = openFundedAccounts(new BigDecimal("100.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> attempts = new ArrayList<>();
            for (UUID destinationId : List.of(fixture.destinationId, fixture.otherDestinationId)) {
                attempts.add(pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        return "START_TIMEOUT";
                    }
                    try {
                        transferService.transfer(
                                fixture.owner,
                                "spend-" + destinationId,
                                new CreateTransferRequest(fixture.sourceId, destinationId, new BigDecimal("80.00"), "EUR")
                        );
                        return "COMPLETED";
                    } catch (BankFlowException ex) {
                        return ex.getStatus().value() + " " + ex.getMessage();
                    }
                }));
            }
            start.countDown();

            List<String> results = new ArrayList<>();
            for (Future<String> attempt : attempts) {
                results.add(attempt.get(20, TimeUnit.SECONDS));
            }

            assertEquals(1, results.stream().filter("COMPLETED"::equals).count(), results.toString());
            assertEquals(1, results.stream().filter(result -> result.contains("Insufficient balance.")).count(), results.toString());

            Account source = accountRepository.findById(fixture.sourceId).orElseThrow();
            Account firstDestination = accountRepository.findById(fixture.destinationId).orElseThrow();
            Account secondDestination = accountRepository.findById(fixture.otherDestinationId).orElseThrow();
            assertEquals(0, source.getBalance().compareTo(new BigDecimal("20.0000")));
            assertTrue(source.getBalance().signum() >= 0);
            assertEquals(0, firstDestination.getBalance().add(secondDestination.getBalance()).compareTo(new BigDecimal("80.0000")));
            assertEquals(1, transferRepository.countBySourceAccount_Id(fixture.sourceId));
        } finally {
            pool.shutdownNow();
            deleteFixture(fixture);
        }
    }

    @Test
    void oppositeTransfersDoNotDeadlock() throws Exception {
        Fixture fixture = openFundedAccounts(new BigDecimal("100.0000"), new BigDecimal("100.0000"), new BigDecimal("0.0000"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<String> forward = pool.submit(() -> transferAfter(start, fixture.owner, "forward-key", fixture.sourceId, fixture.destinationId, "40.00"));
            Future<String> reverse = pool.submit(() -> transferAfter(
                    start,
                    fixture.destinationOwner,
                    "reverse-key",
                    fixture.destinationId,
                    fixture.sourceId,
                    "30.00"
            ));
            start.countDown();

            assertEquals("COMPLETED", forward.get(20, TimeUnit.SECONDS));
            assertEquals("COMPLETED", reverse.get(20, TimeUnit.SECONDS));

            Account source = accountRepository.findById(fixture.sourceId).orElseThrow();
            Account destination = accountRepository.findById(fixture.destinationId).orElseThrow();
            assertEquals(0, source.getBalance().compareTo(new BigDecimal("90.0000")));
            assertEquals(0, destination.getBalance().compareTo(new BigDecimal("110.0000")));
            assertTrue(source.getBalance().signum() >= 0);
            assertTrue(destination.getBalance().signum() >= 0);
            assertEquals(1, transferRepository.countBySourceAccount_Id(fixture.sourceId));
            assertEquals(1, transferRepository.countBySourceAccount_Id(fixture.destinationId));
        } finally {
            pool.shutdownNow();
            deleteFixture(fixture);
        }
    }

    @Test
    void pessimisticLockBlocksASecondUpdateOfTheSameAccount() throws Exception {
        Fixture fixture = openFundedAccounts(new BigDecimal("10.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                accountRepository.findByIdForUpdate(fixture.sourceId).orElseThrow();
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("lock holder was not released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }));

            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<Throwable> waiter = pool.submit(() -> {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        entityManager.unwrap(Session.class).doWork(connection -> {
                            try (var statement = connection.createStatement()) {
                                statement.execute("SET LOCAL lock_timeout = '1000ms'");
                            }
                        });
                        accountRepository.findByIdForUpdate(fixture.sourceId).orElseThrow();
                    });
                    return null;
                } catch (RuntimeException ex) {
                    return ex;
                }
            });

            Throwable failure = waiter.get(8, TimeUnit.SECONDS);
            assertNotNull(failure, "second transaction acquired the row while it was locked");
            assertTrue(isLockFailure(failure), failure.toString());
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            deleteFixture(fixture);
        }
    }

    @Test
    void concurrentDuplicateIdempotencyKeyMovesMoneyOnce() throws Exception {
        Fixture fixture = openFundedAccounts(new BigDecimal("100.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CreateTransferRequest request = new CreateTransferRequest(
                    fixture.sourceId,
                    fixture.destinationId,
                    new BigDecimal("80.00"),
                    "EUR"
            );
            List<Future<TransferResult>> attempts = new ArrayList<>();
            for (int attempt = 0; attempt < 2; attempt++) {
                attempts.add(pool.submit(() -> {
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("start timeout");
                        }
                        return transferService.transfer(fixture.owner, "same-key", request);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }));
            }
            start.countDown();

            TransferResult first = attempts.get(0).get(20, TimeUnit.SECONDS);
            TransferResult second = attempts.get(1).get(20, TimeUnit.SECONDS);
            assertEquals(first.transfer().id(), second.transfer().id());
            assertEquals(1, List.of(first, second).stream().filter(TransferResult::created).count());
            assertEquals(1, List.of(first, second).stream().filter(result -> !result.created()).count());

            Account source = accountRepository.findById(fixture.sourceId).orElseThrow();
            Account destination = accountRepository.findById(fixture.destinationId).orElseThrow();
            assertEquals(0, source.getBalance().compareTo(new BigDecimal("20.0000")));
            assertEquals(0, destination.getBalance().compareTo(new BigDecimal("80.0000")));
            assertEquals(1, transferRepository.countBySourceAccount_Id(fixture.sourceId));
            assertEquals(1, idempotencyKeyRepository.countByUser_Id(fixture.ownerId));
        } finally {
            pool.shutdownNow();
            deleteFixture(fixture);
        }
    }

    private String transferAfter(
            CountDownLatch start,
            AuthenticatedUser owner,
            String idempotencyKey,
            UUID sourceId,
            UUID destinationId,
            String amount
    ) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                return "START_TIMEOUT";
            }
            transferService.transfer(owner, idempotencyKey, new CreateTransferRequest(sourceId, destinationId, new BigDecimal(amount), "EUR"));
            return "COMPLETED";
        } catch (BankFlowException ex) {
            return ex.getStatus().value() + " " + ex.getMessage();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "INTERRUPTED";
        }
    }

    private boolean isLockFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String type = current.getClass().getName();
            if (type.contains("PessimisticLock") || type.contains("LockTimeout") || type.contains("CannotAcquireLock")) {
                return true;
            }
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (message.contains("lock timeout") || message.contains("could not obtain lock") || message.contains("canceling statement due to lock timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Fixture openFundedAccounts(BigDecimal sourceBalance, BigDecimal destinationBalance, BigDecimal otherBalance) {
        return transactionTemplate.execute(status -> {
            User owner = userRepository.save(User.createCustomer(email(), "hash", "Concurrent", "Owner"));
            User receiver = userRepository.save(User.createCustomer(email(), "hash", "Concurrent", "Receiver"));
            User other = userRepository.save(User.createCustomer(email(), "hash", "Concurrent", "Other"));
            Account source = accountRepository.save(Account.open(owner, accountNumber(), Currency.EUR));
            Account destination = accountRepository.save(Account.open(receiver, accountNumber(), Currency.EUR));
            Account otherDestination = accountRepository.save(Account.open(other, accountNumber(), Currency.EUR));
            source.credit(sourceBalance);
            destination.credit(destinationBalance);
            otherDestination.credit(otherBalance);
            return new Fixture(
                    owner.getId(),
                    receiver.getId(),
                    other.getId(),
                    new AuthenticatedUser(owner.getId(), Role.CUSTOMER),
                    new AuthenticatedUser(receiver.getId(), Role.CUSTOMER),
                    source.getId(),
                    destination.getId(),
                    otherDestination.getId()
            );
        });
    }

    private void deleteFixture(Fixture fixture) {
        if (fixture == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            UUID sourceId = fixture.sourceId;
            UUID destinationId = fixture.destinationId;
            UUID otherId = fixture.otherDestinationId;
            jdbcTemplate.update(
                    "delete from idempotency_keys where user_id in (?, ?, ?)",
                    fixture.ownerId,
                    fixture.destinationOwnerId,
                    fixture.otherOwnerId
            );
            jdbcTemplate.update("""
                    delete from outbox_events
                    where aggregate_id in (
                        select id from transfers
                        where source_account_id in (?, ?, ?) or destination_account_id in (?, ?, ?)
                    )
                    """, sourceId, destinationId, otherId, sourceId, destinationId, otherId);
            jdbcTemplate.update("delete from transactions where account_id in (?, ?, ?)", sourceId, destinationId, otherId);
            jdbcTemplate.update(
                    "delete from transfers where source_account_id in (?, ?, ?) or destination_account_id in (?, ?, ?)",
                    sourceId, destinationId, otherId, sourceId, destinationId, otherId
            );
            jdbcTemplate.update("delete from accounts where id in (?, ?, ?)", sourceId, destinationId, otherId);
            jdbcTemplate.update("delete from users where id in (?, ?, ?)", fixture.ownerId, fixture.destinationOwnerId, fixture.otherOwnerId);
        });
    }

    private String accountNumber() {
        return "5" + String.format("%011d", ThreadLocalRandom.current().nextLong(100_000_000_000L));
    }

    private String email() {
        return "concurrent-" + UUID.randomUUID() + "@example.com";
    }

    private record Fixture(
            UUID ownerId,
            UUID destinationOwnerId,
            UUID otherOwnerId,
            AuthenticatedUser owner,
            AuthenticatedUser destinationOwner,
            UUID sourceId,
            UUID destinationId,
            UUID otherDestinationId
    ) {
    }
}
