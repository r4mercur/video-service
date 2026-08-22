package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.delivery.MediaUrlResolver;

import java.time.Instant;
import java.util.UUID;

/**
 * Kein playlistKey/playlistUrl hier - die Wiedergabe-URL kommt bewusst ausschliesslich ueber
 * {@code GET /api/videos/{id}/manifest} (delivery-Paket, AP6), das fuer PRIVATE-Videos zur
 * Laufzeit signiert werden muss und daher nicht als statisches Detail-Feld passt.
 */
public record VideoDetailDto(UUID id, String slug, String title, String description, String thumbnailUrl,
                              Integer durationSeconds, Integer width, Integer height, String categorySlug,
                              String ownerUsername, Visibility visibility, VideoStatus status, Instant publishedAt,
                              Instant createdAt) {

    public static VideoDetailDto from(Video video, MediaUrlResolver urlResolver) {
        return new VideoDetailDto(video.getId(), video.getSlug(), video.getTitle(), video.getDescription(),
                urlResolver.resolve(video.getVisibility(), video.getThumbnailKey()), video.getDurationSeconds(),
                video.getWidth(), video.getHeight(), video.getCategory().getSlug(), video.getUser().getUsername(),
                video.getVisibility(), video.getStatus(), video.getPublishedAt(), video.getCreatedAt());
    }
}
