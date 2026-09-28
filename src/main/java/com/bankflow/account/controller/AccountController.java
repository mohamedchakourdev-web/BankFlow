package com.bankflow.account.controller;

import com.bankflow.account.dto.AccountResponse;
import com.bankflow.account.dto.CreateAccountRequest;
import com.bankflow.account.service.AccountService;
import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.exception.BankFlowException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Open an account", description = "Balance starts at 0.0000. The client cannot set the balance.")
    public ResponseEntity<AccountResponse> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateAccountRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.create(requireUser(principal), request));
    }

    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return accountService.list(requireUser(principal));
    }

    @GetMapping("/{id}")
    public AccountResponse get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID id
    ) {
        return accountService.get(requireUser(principal), id);
    }

    private AuthenticatedUser requireUser(AuthenticatedUser principal) {
        if (principal == null) {
            throw new BankFlowException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        return principal;
    }
}
