package com.bankflow.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record RegisterRequest(
        @Schema(example = "Ada")
        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name must be at most 100 characters")
        String firstName,
        @Schema(example = "Lovelace")
        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name must be at most 100 characters")
        String lastName,
        @Schema(example = "ada@example.com")
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,
        @Schema(example = "example-pass-1")
        @NotBlank(message = "Password is required")
        @PasswordPolicy
        String password
) {

    public RegisterRequest {
        firstName = firstName == null ? null : firstName.trim();
        lastName = lastName == null ? null : lastName.trim();
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + "]";
    }
}
