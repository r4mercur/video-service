package com.bjarne.videoservice.moderation.dto;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.entity.VideoStatus;
import com.bjarne.videoservice.moderation.entity.Report;
import com.bjarne.videoservice.moderation.entity.ReportStatus;

import java.time.Instant;
import java.util.UUID;

public record AdminReportDto(Long id, UUID videoId, String videoSlug, String videoTitle, VideoStatus videoStatus,
                              String reporterUsername, String reason, String detail, ReportStatus status,
                              Instant createdAt) {

    public static AdminReportDto from(Report report) {
        Video video = report.getVideo();
        String reporterUsername = report.getReporter() != null ? report.getReporter().getUsername() : null;
        return new AdminReportDto(report.getId(), video.getId(), video.getSlug(), video.getTitle(),
                video.getStatus(), reporterUsername, report.getReason(), report.getDetail(),
                report.getStatus(), report.getCreatedAt());
    }
}
