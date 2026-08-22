package com.bjarne.videoservice.catalog;

import java.util.UUID;

/**
 * Einzige Stelle fuer Sichtbarkeitslogik (CLAUDE.md 3.2) - wird von Katalog-Queries
 * und dem Detail-Endpunkt genutzt, damit die Regel nicht dupliziert wird.
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
