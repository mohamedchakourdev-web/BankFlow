package com.bankflow.auth.dto;

public record AuthResponse(String token, UserResponse user) {
}
