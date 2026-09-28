package com.bankflow.auth.controller;

import com.bankflow.auth.dto.AuthResponse;
import com.bankflow.auth.dto.LoginRequest;
import com.bankflow.auth.dto.RegisterRequest;
import com.bankflow.auth.dto.UserResponse;
import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.auth.service.AuthService;
import com.bankflow.common.exception.BankFlowException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @SecurityRequirements
    @Operation(summary = "Register a customer", description = "Creates a CUSTOMER. There is no public admin registration. Example password is fake.")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Log in", description = "Returns a bearer token. Send it as Authorization: Bearer <token>.")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    @Operation(summary = "Current user")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new BankFlowException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        return authService.currentUser(principal.id());
    }
}
