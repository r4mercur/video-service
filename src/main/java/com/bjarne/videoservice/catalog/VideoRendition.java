package com.bjarne.videoservice.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "video_renditions")
public class VideoRendition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    @Column(nullable = false)
    private int height;

    @Column(name = "bitrate_kbps", nullable = false)
    private int bitrateKbps;

    @Column(name = "playlist_key", nullable = false)
    private String playlistKey;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    protected VideoRendition() {
    }

    public VideoRendition(Video video, int height, int bitrateKbps, String playlistKey, long sizeBytes) {
        this.video = video;
        this.height = height;
        this.bitrateKbps = bitrateKbps;
        this.playlistKey = playlistKey;
        this.sizeBytes = sizeBytes;
    }

    public Long getId() {
        return id;
    }

    public Video getVideo() {
        return video;
    }

    public int getHeight() {
        return height;
    }

    public int getBitrateKbps() {
        return bitrateKbps;
    }

    public String getPlaylistKey() {
        return playlistKey;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }
}
