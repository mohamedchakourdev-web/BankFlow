package com.bankflow.transaction.controller;

import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.exception.BankFlowException;
import com.bankflow.transaction.dto.TransactionResponse;
import com.bankflow.transaction.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@Tag(name = "Transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    @Operation(summary = "List transfer history", description = "Newest first. Default size 20, maximum 100.")
    public Page<TransactionResponse> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID accountId
    ) {
        return transactionService.list(requireUser(principal), page, size, accountId);
    }

    @GetMapping("/{id}")
    public TransactionResponse get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID id
    ) {
        return transactionService.get(requireUser(principal), id);
    }

    private AuthenticatedUser requireUser(AuthenticatedUser principal) {
        if (principal == null) {
            throw new BankFlowException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        return principal;
    }
}
