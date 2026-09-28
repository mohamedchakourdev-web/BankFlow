package com.bankflow.auth.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bankflow.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @Min(1) long expirationSeconds
) {
}
