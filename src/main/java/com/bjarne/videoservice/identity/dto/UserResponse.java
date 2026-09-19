package com.bjarne.videoservice.identity.dto;

import com.bjarne.videoservice.delivery.service.MediaUrlResolver;
import com.bjarne.videoservice.identity.entity.Role;
import com.bjarne.videoservice.identity.entity.User;
import com.bjarne.videoservice.identity.entity.UserStatus;

import java.time.Instant;
import java.util.UUID;

/** avatarUrl is null without a profile photo - the frontend then shows the initials. */
public record UserResponse(
        UUID id,
        String email,
        String username,
        Role role,
        UserStatus status,
        Instant createdAt,
        String avatarUrl) {

    public static UserResponse from(User user, MediaUrlResolver urlResolver) {
        return new UserResponse(user.getId(), user.getEmail(), user.getUsername(), user.getRole(), user.getStatus(),
                user.getCreatedAt(), urlResolver.resolvePublic(user.getAvatarKey()));
    }
}
