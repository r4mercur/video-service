package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.identity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "videos")
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @Column(nullable = false, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VideoStatus status = VideoStatus.UPLOADING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Visibility visibility;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "storage_prefix")
    private String storagePrefix;

    @Column(name = "playlist_key")
    private String playlistKey;

    @Column(name = "thumbnail_key")
    private String thumbnailKey;

    @Column(name = "has_custom_thumbnail", nullable = false)
    private boolean hasCustomThumbnail = false;

    @Column(name = "source_key")
    private String sourceKey;

    @Column(name = "source_deleted_at")
    private Instant sourceDeletedAt;

    @Column(name = "sprite_sheet_key")
    private String spriteSheetKey;

    @Column(name = "published_at")
    private Instant publishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Video() {
    }

    public Video(User user, Category category, String title, String slug, Visibility visibility) {
        this.user = user;
        this.category = category;
        this.title = title;
        this.slug = slug;
        this.visibility = visibility;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSlug() {
        return slug;
    }

    public VideoStatus getStatus() {
        return status;
    }

    public void setStatus(VideoStatus status) {
        this.status = status;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public void setVisibility(Visibility visibility) {
        this.visibility = visibility;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getStoragePrefix() {
        return storagePrefix;
    }

    public void setStoragePrefix(String storagePrefix) {
        this.storagePrefix = storagePrefix;
    }

    public String getPlaylistKey() {
        return playlistKey;
    }

    public void setPlaylistKey(String playlistKey) {
        this.playlistKey = playlistKey;
    }

    public String getThumbnailKey() {
        return thumbnailKey;
    }

    public void setThumbnailKey(String thumbnailKey) {
        this.thumbnailKey = thumbnailKey;
    }

    public boolean isHasCustomThumbnail() {
        return hasCustomThumbnail;
    }

    public void setHasCustomThumbnail(boolean hasCustomThumbnail) {
        this.hasCustomThumbnail = hasCustomThumbnail;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public Instant getSourceDeletedAt() {
        return sourceDeletedAt;
    }

    public void setSourceDeletedAt(Instant sourceDeletedAt) {
        this.sourceDeletedAt = sourceDeletedAt;
    }

    public String getSpriteSheetKey() {
        return spriteSheetKey;
    }

    public void setSpriteSheetKey(String spriteSheetKey) {
        this.spriteSheetKey = spriteSheetKey;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
