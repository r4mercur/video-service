package com.bjarne.videoservice.catalog;

/**
 * CLAUDE.md 9.5: PATCH /api/videos/{id} returns 202 Accepted when the payload changes visibility
 * (the move runs as a background job) and 200 OK otherwise.
 */
public record UpdateVideoResult(VideoDetailDto video, boolean visibilityMigrationEnqueued) {
}
