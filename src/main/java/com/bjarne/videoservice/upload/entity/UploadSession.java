package com.bjarne.videoservice.upload.entity;

import com.bjarne.videoservice.catalog.entity.Video;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "upload_sessions")
public class UploadSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    @Column(name = "s3_upload_id", nullable = false)
    private String s3UploadId;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected UploadSession() {
    }

    public UploadSession(Video video, String s3UploadId, String s3Key, Instant expiresAt) {
        this.video = video;
        this.s3UploadId = s3UploadId;
        this.s3Key = s3Key;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Video getVideo() {
        return video;
    }

    public String getS3UploadId() {
        return s3UploadId;
    }

    public String getS3Key() {
        return s3Key;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
