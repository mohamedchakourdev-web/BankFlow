package com.bankflow.auth.service;

import com.bankflow.auth.dto.AuthResponse;
import com.bankflow.auth.dto.LoginRequest;
import com.bankflow.auth.dto.RegisterRequest;
import com.bankflow.auth.dto.UserResponse;
import com.bankflow.auth.security.JwtService;
import com.bankflow.common.exception.BankFlowException;
import com.bankflow.audit.AuditService;
import com.bankflow.user.User;
import com.bankflow.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public static final String INVALID_CREDENTIALS = "Invalid email or password.";
    public static final String EMAIL_ALREADY_REGISTERED = "An account with this email already exists.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final String invalidPasswordHash;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuditService auditService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.invalidPasswordHash = passwordEncoder.encode("bankflow-invalid-credential-placeholder");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BankFlowException(HttpStatus.CONFLICT, EMAIL_ALREADY_REGISTERED);
        }

        User user = User.createCustomer(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.firstName(),
                request.lastName()
        );
        User saved = userRepository.saveAndFlush(user);
        auditService.userRegistered(saved);
        log.info("Registered user id={} role={}", saved.getId(), saved.getRole());
        return new AuthResponse(jwtService.generateToken(saved.getId(), saved.getRole()), UserResponse.from(saved));
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email()).orElse(null);
        String hash = user == null ? invalidPasswordHash : user.getPassword();
        if (user == null || !passwordEncoder.matches(request.password(), hash)) {
            auditService.loginFailed();
            log.info("Login failed");
            throw new BankFlowException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }
        auditService.loginSucceeded(user);
        log.info("Login succeeded userId={}", user.getId());
        return new AuthResponse(jwtService.generateToken(user.getId(), user.getRole()), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(UUID userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new BankFlowException(HttpStatus.UNAUTHORIZED, "Authentication is required."));
    }
}
