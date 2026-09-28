package com.bankflow.account.service;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.bankflow.account.dto.AccountResponse;
import com.bankflow.account.dto.CreateAccountRequest;
import com.bankflow.audit.AuditService;
import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.Currency;
import com.bankflow.common.exception.BankFlowException;
import com.bankflow.user.Role;
import com.bankflow.user.User;
import com.bankflow.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    static final int ACCOUNT_NUMBER_LENGTH = 12;
    private static final int MAX_ALLOCATION_ATTEMPTS = 5;
    private static final String ACCOUNT_NOT_FOUND = "Account not found";

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository accountRepository, UserRepository userRepository, AuditService auditService) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    public AccountResponse create(AuthenticatedUser principal, CreateAccountRequest request) {
        User owner = userRepository.findById(principal.id())
                .orElseThrow(() -> new BankFlowException(HttpStatus.UNAUTHORIZED, "Authentication is required."));
        Currency currency = Currency.valueOf(request.currency());
        Account account = Account.open(owner, allocateAccountNumber(), currency);
        Account saved = accountRepository.saveAndFlush(account);
        auditService.accountCreated(owner, saved);
        return AccountResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(AuthenticatedUser principal) {
        List<Account> accounts = principal.role() == Role.ADMIN
                ? accountRepository.findAllByOrderByCreatedAtAsc()
                : accountRepository.findAllByUser_IdOrderByCreatedAtAsc(principal.id());
        return accounts.stream().map(AccountResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(AuthenticatedUser principal, UUID accountId) {
        Account account = principal.role() == Role.ADMIN
                ? accountRepository.findById(accountId).orElseThrow(this::notFound)
                : accountRepository.findByIdAndUser_Id(accountId, principal.id()).orElseThrow(this::notFound);
        return AccountResponse.from(account);
    }

    private String allocateAccountNumber() {
        for (int attempt = 0; attempt < MAX_ALLOCATION_ATTEMPTS; attempt++) {
            String candidate = randomAccountNumber();
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new BankFlowException(HttpStatus.CONFLICT, "Could not allocate an account number");
    }

    private String randomAccountNumber() {
        StringBuilder number = new StringBuilder(ACCOUNT_NUMBER_LENGTH);
        number.append(random.nextInt(9) + 1);
        for (int i = 1; i < ACCOUNT_NUMBER_LENGTH; i++) {
            number.append(random.nextInt(10));
        }
        return number.toString();
    }

    private BankFlowException notFound() {
        return new BankFlowException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND);
    }
}
