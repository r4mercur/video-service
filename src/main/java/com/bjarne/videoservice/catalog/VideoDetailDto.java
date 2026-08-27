package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.delivery.MediaUrlResolver;

import java.time.Instant;
import java.util.UUID;

/**
 * No playlistKey/playlistUrl here - the playback URL deliberately comes exclusively via
 * {@code GET /api/videos/{id}/manifest} (delivery package, AP6), which must be signed at
 * runtime for PRIVATE videos and therefore doesn't fit as a static detail field.
 */
public record VideoDetailDto(UUID id, String slug, String title, String description, String thumbnailUrl,
                              boolean hasCustomThumbnail, Integer durationSeconds, Integer width, Integer height,
                              String categorySlug, String ownerUsername, Visibility visibility, VideoStatus status,
                              Instant publishedAt, Instant createdAt) {

    public static VideoDetailDto from(Video video, MediaUrlResolver urlResolver) {
        return new VideoDetailDto(video.getId(), video.getSlug(), video.getTitle(), video.getDescription(),
                urlResolver.resolve(video.getVisibility(), video.getThumbnailKey()), video.isHasCustomThumbnail(),
                video.getDurationSeconds(), video.getWidth(), video.getHeight(), video.getCategory().getSlug(),
                video.getUser().getUsername(), video.getVisibility(), video.getStatus(), video.getPublishedAt(),
                video.getCreatedAt());
    }
}
