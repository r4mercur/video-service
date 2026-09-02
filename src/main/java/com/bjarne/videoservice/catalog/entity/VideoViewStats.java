package com.bjarne.videoservice.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "video_view_stats")
public class VideoViewStats {

    @EmbeddedId
    private VideoViewStatsId id;

    @Column(nullable = false)
    private long views;

    protected VideoViewStats() {
    }

    public VideoViewStats(VideoViewStatsId id) {
        this.id = id;
    }

    public VideoViewStatsId getId() {
        return id;
    }

    public long getViews() {
        return views;
    }

    public void setViews(long views) {
        this.views = views;
    }
}
