package com.bankflow.auth.dto;

import com.bankflow.user.Role;
import com.bankflow.user.User;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        Role role
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole()
        );
    }
}
