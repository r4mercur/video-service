package com.bjarne.videoservice.catalog;

import java.time.Instant;
import java.util.UUID;

public record VideoSummaryDto(UUID id, String slug, String title, String thumbnailKey, Integer durationSeconds,
                               String categorySlug, String ownerUsername, Instant publishedAt) {

    public static VideoSummaryDto from(Video video) {
        return new VideoSummaryDto(video.getId(), video.getSlug(), video.getTitle(), video.getThumbnailKey(),
                video.getDurationSeconds(), video.getCategory().getSlug(), video.getUser().getUsername(),
                video.getPublishedAt());
    }
}
