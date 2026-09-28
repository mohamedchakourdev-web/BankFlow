package com.bankflow.transfer.service;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.bankflow.account.AccountStatus;
import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.Currency;
import com.bankflow.common.exception.BankFlowException;
import com.bankflow.idempotency.IdempotencyKey;
import com.bankflow.idempotency.IdempotencyKeyRepository;
import com.bankflow.idempotency.IdempotencyStatus;
import com.bankflow.idempotency.TransferRequestFingerprint;
import com.bankflow.outbox.OutboxEventService;
import com.bankflow.outbox.OutboxInsertProbe;
import com.bankflow.transfer.Transfer;
import com.bankflow.transfer.dto.CreateTransferRequest;
import com.bankflow.transfer.dto.TransferResponse;
import com.bankflow.transfer.dto.TransferResult;
import com.bankflow.transfer.repository.TransferRepository;
import com.bankflow.user.User;
import com.bankflow.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    static final String SOURCE_NOT_FOUND = "Source account not found";
    static final String DESTINATION_NOT_FOUND = "Destination account not found";
    static final String SAME_ACCOUNT = "Source and destination accounts must be different";
    static final String INSUFFICIENT_BALANCE = "Insufficient balance.";
    static final String CURRENCIES_MUST_MATCH = "Currencies must match";
    static final String AMOUNT_INVALID = "Amount must be greater than zero";
    static final String KEY_REQUIRED = "Idempotency-Key is required";
    static final String KEY_EMPTY = "Idempotency-Key must not be empty";
    static final String KEY_TOO_LONG = "Idempotency-Key must be at most 128 characters";
    static final String KEY_REUSED = "Idempotency key was already used for a different request";
    static final int MAX_KEY_LENGTH = 128;
    private static final String IDEMPOTENCY_CONSTRAINT = "idempotency_keys_user_key_unique";

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final TransferReferenceGenerator referenceGenerator;
    private final UserRepository userRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OutboxEventService outboxEventService;
    private final OutboxInsertProbe outboxInsertProbe;
    private final TransactionTemplate transactionTemplate;

    public TransferService(
            AccountRepository accountRepository,
            TransferRepository transferRepository,
            TransferReferenceGenerator referenceGenerator,
            UserRepository userRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            OutboxEventService outboxEventService,
            OutboxInsertProbe outboxInsertProbe,
            TransactionTemplate transactionTemplate
    ) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.referenceGenerator = referenceGenerator;
        this.userRepository = userRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.outboxEventService = outboxEventService;
        this.outboxInsertProbe = outboxInsertProbe;
        this.transactionTemplate = transactionTemplate;
    }

    public TransferResult transfer(AuthenticatedUser principal, String idempotencyKey, CreateTransferRequest request) {
        if (principal == null) {
            throw new BankFlowException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        String key = normalizeKey(idempotencyKey);
        rejectInvalidAmount(request.amount());
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, SAME_ACCOUNT);
        }
        String fingerprint = TransferRequestFingerprint.of(request);
        try {
            return transactionTemplate.execute(status -> perform(principal, key, fingerprint, request));
        } catch (RuntimeException ex) {
            if (!isIdempotencyConflict(ex)) {
                throw ex;
            }
            return transactionTemplate.execute(status -> replay(principal, key, fingerprint));
        }
    }

    private TransferResult perform(
            AuthenticatedUser principal,
            String key,
            String fingerprint,
            CreateTransferRequest request
    ) {
        User user = userRepository.findById(principal.id())
                .orElseThrow(() -> new BankFlowException(HttpStatus.UNAUTHORIZED, "Authentication is required."));
        IdempotencyKey claim = idempotencyKeyRepository.saveAndFlush(IdempotencyKey.start(user, key, fingerprint));

        UUID lowerId = lower(request.sourceAccountId(), request.destinationAccountId());
        UUID higherId = lowerId.equals(request.sourceAccountId())
                ? request.destinationAccountId()
                : request.sourceAccountId();

        Account lowerAccount = lock(lowerId, request);
        Account higherAccount = lock(higherId, request);
        Account source = lowerAccount.getId().equals(request.sourceAccountId()) ? lowerAccount : higherAccount;
        Account destination = source == lowerAccount ? higherAccount : lowerAccount;

        if (!source.getUser().getId().equals(principal.id())) {
            throw new BankFlowException(HttpStatus.NOT_FOUND, SOURCE_NOT_FOUND);
        }
        requireActive(source, "Source account is not active");
        requireActive(destination, "Destination account is not active");

        if (source.getCurrency() != destination.getCurrency()) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, CURRENCIES_MUST_MATCH);
        }
        if (request.currency() != null && currency(request.currency()) != source.getCurrency()) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, CURRENCIES_MUST_MATCH);
        }

        BigDecimal amount = money(request.amount());
        if (source.getBalance().compareTo(amount) < 0) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, INSUFFICIENT_BALANCE);
        }

        source.debit(amount);
        destination.credit(amount);
        accountRepository.saveAndFlush(source);
        accountRepository.saveAndFlush(destination);

        Transfer transfer = transferRepository.saveAndFlush(Transfer.completed(
                source,
                destination,
                amount,
                source.getCurrency(),
                referenceGenerator.next()
        ));
        outboxEventService.enqueue(transfer);
        outboxInsertProbe.afterInsert();
        claim.complete(transfer);
        idempotencyKeyRepository.saveAndFlush(claim);
        log.info("Transfer completed transferId={} userId={}", transfer.getId(), principal.id());
        return new TransferResult(TransferResponse.from(transfer), true);
    }

    private TransferResult replay(AuthenticatedUser principal, String key, String fingerprint) {
        IdempotencyKey existing = idempotencyKeyRepository.findByUserIdAndKeyForUpdate(principal.id(), key)
                .orElseThrow(() -> new BankFlowException(HttpStatus.CONFLICT, KEY_REUSED));
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new BankFlowException(HttpStatus.CONFLICT, KEY_REUSED);
        }
        if (existing.getStatus() != IdempotencyStatus.COMPLETED || existing.getTransfer() == null) {
            throw new BankFlowException(HttpStatus.CONFLICT, "Idempotency key is already in progress");
        }
        return new TransferResult(TransferResponse.from(existing.getTransfer()), false);
    }

    private String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, KEY_REQUIRED);
        }
        String key = idempotencyKey.trim();
        if (key.isEmpty()) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, KEY_EMPTY);
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, KEY_TOO_LONG);
        }
        return key;
    }

    private boolean isIdempotencyConflict(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException) {
                String message = current.getMessage();
                Throwable cause = current.getCause();
                if (containsConstraint(message) || (cause != null && containsConstraint(cause.getMessage()))) {
                    return true;
                }
            }
            if (containsConstraint(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsConstraint(String message) {
        return message != null && message.contains(IDEMPOTENCY_CONSTRAINT);
    }

    private Account lock(UUID id, CreateTransferRequest request) {
        return accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BankFlowException(HttpStatus.NOT_FOUND, missingMessage(id, request)));
    }

    private String missingMessage(UUID id, CreateTransferRequest request) {
        return id.equals(request.sourceAccountId()) ? SOURCE_NOT_FOUND : DESTINATION_NOT_FOUND;
    }

    private void requireActive(Account account, String message) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private void rejectInvalidAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, AMOUNT_INVALID);
        }
    }

    private BigDecimal money(BigDecimal amount) {
        try {
            return amount.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, "Amount must be a valid decimal");
        }
    }

    private Currency currency(String value) {
        try {
            return Currency.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, "Currency must be USD, EUR, or GBP");
        }
    }

    private UUID lower(UUID left, UUID right) {
        return left.compareTo(right) <= 0 ? left : right;
    }
}
