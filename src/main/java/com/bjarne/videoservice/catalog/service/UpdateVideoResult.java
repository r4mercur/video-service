package com.bjarne.videoservice.catalog.service;

import com.bjarne.videoservice.catalog.dto.VideoDetailDto;

/**
 * CLAUDE.md 9.5: PATCH /api/videos/{id} returns 202 Accepted when the payload changes visibility
 * (the move runs as a background job) and 200 OK otherwise.
 */
public record UpdateVideoResult(VideoDetailDto video, boolean visibilityMigrationEnqueued) {
}
