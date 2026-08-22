package com.bjarne.videoservice.catalog;

import java.time.Instant;
import java.util.UUID;

public record VideoDetailDto(UUID id, String slug, String title, String description, String thumbnailKey,
                              String playlistKey, Integer durationSeconds, Integer width, Integer height,
                              String categorySlug, String ownerUsername, Visibility visibility, VideoStatus status,
                              Instant publishedAt, Instant createdAt) {

    public static VideoDetailDto from(Video video) {
        return new VideoDetailDto(video.getId(), video.getSlug(), video.getTitle(), video.getDescription(),
                video.getThumbnailKey(), video.getPlaylistKey(), video.getDurationSeconds(), video.getWidth(),
                video.getHeight(), video.getCategory().getSlug(), video.getUser().getUsername(),
                video.getVisibility(), video.getStatus(), video.getPublishedAt(), video.getCreatedAt());
    }
}
