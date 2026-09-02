package com.bjarne.videoservice.identity.dto;

import com.bjarne.videoservice.identity.entity.Role;
import com.bjarne.videoservice.identity.entity.User;
import com.bjarne.videoservice.identity.entity.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String username,
        Role role,
        UserStatus status,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getUsername(), user.getRole(), user.getStatus(),
                user.getCreatedAt());
    }
}
