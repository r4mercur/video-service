package com.bjarne.videoservice.catalog.entity;

public enum VideoStatus {
    UPLOADING,
    PROCESSING,
    READY,
    FAILED,
    BLOCKED,
    /**
     * DELETE was accepted and a VIDEO_DELETION job is emptying storage (CLAUDE.md 9.7). The video
     * is gone for everyone, its owner included, and never returns to another status - some of its
     * objects may already be deleted.
     */
    DELETING
}
