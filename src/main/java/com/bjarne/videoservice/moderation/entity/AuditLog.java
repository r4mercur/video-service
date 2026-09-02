package com.bjarne.videoservice.moderation.entity;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.identity.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Immutable record of every moderation action (CLAUDE.md 12, DSA justification requirement) -
 * deliberately without setters, an audit entry is never edited after the fact.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditLogAction action;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id")
    private Video video;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id")
    private Report report;

    @Column(nullable = false)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    public AuditLog(User actor, AuditLogAction action, Video video, Report report, String reason) {
        this.actor = actor;
        this.action = action;
        this.video = video;
        this.report = report;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public User getActor() {
        return actor;
    }

    public AuditLogAction getAction() {
        return action;
    }

    public Video getVideo() {
        return video;
    }

    public Report getReport() {
        return report;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
