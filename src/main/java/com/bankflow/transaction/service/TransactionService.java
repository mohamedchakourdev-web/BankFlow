package com.bankflow.transaction.service;

import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.exception.BankFlowException;
import com.bankflow.transaction.dto.TransactionResponse;
import com.bankflow.transfer.Transfer;
import com.bankflow.transfer.repository.TransferRepository;
import com.bankflow.user.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TransactionService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final String NOT_FOUND = "Transaction not found";

    private final TransferRepository transferRepository;

    public TransactionService(TransferRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> list(AuthenticatedUser principal, int page, int size, UUID accountId) {
        PageRequest pageRequest = pageRequest(page, size);
        Page<Transfer> transfers;
        if (principal.role() == Role.ADMIN) {
            transfers = accountId == null
                    ? transferRepository.findAll(pageRequest)
                    : transferRepository.findByAccount(accountId, pageRequest);
        } else if (accountId == null) {
            transfers = transferRepository.findInvolvingUser(principal.id(), pageRequest);
        } else {
            transfers = transferRepository.findInvolvingUserAndAccount(principal.id(), accountId, pageRequest);
        }
        return transfers.map(TransactionResponse::from);
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(AuthenticatedUser principal, UUID transactionId) {
        Transfer transfer = principal.role() == Role.ADMIN
                ? transferRepository.findById(transactionId).orElseThrow(this::notFound)
                : transferRepository.findByIdInvolvingUser(transactionId, principal.id()).orElseThrow(this::notFound);
        return TransactionResponse.from(transfer);
    }

    private PageRequest pageRequest(int page, int size) {
        if (page < 0) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, "Page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, "Page size must be between 1 and 100");
        }
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private BankFlowException notFound() {
        return new BankFlowException(HttpStatus.NOT_FOUND, NOT_FOUND);
    }
}
