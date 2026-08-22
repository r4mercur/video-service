package com.bjarne.videoservice.upload;

import com.bjarne.videoservice.catalog.Video;
import com.bjarne.videoservice.catalog.VideoRepository;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Zentrale Ownership-Pruefung fuer @PreAuthorize auf Video-Schreibendpunkten (CLAUDE.md 3.2).
 * Unbekannte Video-ID -> NotFoundException (404), bekannte ID mit fremdem Owner -> false (403).
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
