package com.bankflow.auth.security;

import com.bankflow.user.Role;

import java.time.Instant;
import java.util.UUID;

public record JwtIdentity(UUID userId, Role role, Instant issuedAt, Instant expiresAt) {
}
