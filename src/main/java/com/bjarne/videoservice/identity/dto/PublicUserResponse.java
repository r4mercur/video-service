package com.bjarne.videoservice.identity.dto;

import com.bjarne.videoservice.delivery.service.MediaUrlResolver;
import com.bjarne.videoservice.identity.entity.User;

/**
 * What anyone - logged in or not - may see about a user, e.g. for the channel page header.
 * Deliberately a separate type from {@link UserResponse}: that one carries the email address and
 * must only ever be returned to the user themselves.
 */
public record PublicUserResponse(String username, String avatarUrl) {

    public static PublicUserResponse from(User user, MediaUrlResolver urlResolver) {
        return new PublicUserResponse(user.getUsername(), urlResolver.resolvePublic(user.getAvatarKey()));
    }
}
