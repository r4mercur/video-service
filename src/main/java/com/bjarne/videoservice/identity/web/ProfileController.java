package com.bjarne.videoservice.identity.web;

import com.bjarne.videoservice.delivery.service.MediaUrlResolver;
import com.bjarne.videoservice.identity.dto.PublicUserResponse;
import com.bjarne.videoservice.identity.dto.UserResponse;
import com.bjarne.videoservice.identity.service.AuthService;
import com.bjarne.videoservice.identity.service.AvatarService;
import com.bjarne.videoservice.shared.RateLimiter;
import com.bjarne.videoservice.shared.exceptions.TooManyRequestsException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Profile photo (CLAUDE.md 9.8) and the public profile. The avatar endpoints live under /api/me,
 * so ownership is implicit: the only user they can ever act on is the token's subject - there is
 * no id in the path that a @PreAuthorize check would have to match against.
 */
@RestController
public class ProfileController {

    private final AvatarService avatarService;
    private final AuthService authService;
    private final RateLimiter rateLimiter;
    private final MediaUrlResolver urlResolver;

    public ProfileController(AvatarService avatarService, AuthService authService, RateLimiter rateLimiter,
                             MediaUrlResolver urlResolver) {
        this.avatarService = avatarService;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.urlResolver = urlResolver;
    }

    @PutMapping("/api/me/avatar")
    public UserResponse setAvatar(@RequestParam("file") MultipartFile file, JwtAuthenticationToken authentication) {
        UUID userId = userId(authentication);
        if (!rateLimiter.tryConsumeAvatarUpload(userId.toString())) {
            throw new TooManyRequestsException("Too many profile photo uploads - please try again later");
        }
        return UserResponse.from(avatarService.store(userId, file), urlResolver);
    }

    @DeleteMapping("/api/me/avatar")
    public UserResponse removeAvatar(JwtAuthenticationToken authentication) {
        return UserResponse.from(avatarService.remove(userId(authentication)), urlResolver);
    }

    @GetMapping("/api/users/{username}")
    public PublicUserResponse publicProfile(@PathVariable String username) {
        return authService.getPublicProfile(username);
    }

    private UUID userId(JwtAuthenticationToken authentication) {
        return UUID.fromString(authentication.getToken().getSubject());
    }
}
