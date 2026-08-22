package com.bjarne.videoservice.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class VideoViewStatsId implements Serializable {

    @Column(name = "video_id")
    private UUID videoId;

    @Column(name = "day")
    private LocalDate day;

    protected VideoViewStatsId() {
    }

    public VideoViewStatsId(UUID videoId, LocalDate day) {
        this.videoId = videoId;
        this.day = day;
    }

    public UUID getVideoId() {
        return videoId;
    }

    public LocalDate getDay() {
        return day;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VideoViewStatsId that)) return false;
        return Objects.equals(videoId, that.videoId) && Objects.equals(day, that.day);
    }

    @Override
    public int hashCode() {
        return Objects.hash(videoId, day);
    }
}
