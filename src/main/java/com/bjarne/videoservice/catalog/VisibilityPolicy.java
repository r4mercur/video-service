package com.bjarne.videoservice.catalog;

import java.util.UUID;

/**
 * The single place for visibility logic (CLAUDE.md 3.2) - used by catalog queries
 * and the detail endpoint so the rule isn't duplicated.
 */
public final class VisibilityPolicy {

    private VisibilityPolicy() {
    }

    public static boolean isPubliclyVisible(Video video) {
        return video.getStatus() == VideoStatus.READY && video.getVisibility() == Visibility.PUBLIC;
    }

    public static boolean isVisibleTo(Video video, UUID viewerUserId) {
        return isPubliclyVisible(video) || (viewerUserId != null && video.getUser().getId().equals(viewerUserId));
    }
}
