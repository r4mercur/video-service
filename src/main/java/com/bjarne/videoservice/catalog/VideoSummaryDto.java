package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.delivery.MediaUrlResolver;

import java.time.Instant;
import java.util.UUID;

public record VideoSummaryDto(UUID id, String slug, String title, String thumbnailUrl, Integer durationSeconds,
                               String categorySlug, String ownerUsername, Instant publishedAt,
                               boolean ageRestricted) {

    public static VideoSummaryDto from(Video video, MediaUrlResolver urlResolver) {
        return new VideoSummaryDto(video.getId(), video.getSlug(), video.getTitle(),
                urlResolver.resolve(video.getVisibility(), video.getThumbnailKey()), video.getDurationSeconds(),
                video.getCategory().getSlug(), video.getUser().getUsername(), video.getPublishedAt(),
                video.getCategory().isAgeRestricted());
    }
}
