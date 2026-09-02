package com.bjarne.videoservice.upload.service;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Central ownership check for @PreAuthorize on video write endpoints (CLAUDE.md 3.2).
 * Unknown video ID -> NotFoundException (404), known ID with a different owner -> false (403).
 */
@Component("videoOwnership")
public class VideoOwnership {

    private final VideoRepository videoRepository;

    public VideoOwnership(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
    }

    public boolean isOwner(UUID videoId, Authentication authentication) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new NotFoundException("Video not found"));
        UUID currentUserId = UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
        return video.getUser().getId().equals(currentUserId);
    }
}
