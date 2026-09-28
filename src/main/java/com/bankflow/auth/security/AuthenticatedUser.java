package com.bankflow.auth.security;

import com.bankflow.user.Role;

import java.util.UUID;

public record AuthenticatedUser(UUID id, Role role) {
}
