package com.bjarne.videoservice.moderation;

import com.bjarne.videoservice.catalog.Video;
import com.bjarne.videoservice.catalog.VideoStatus;

import java.time.Instant;
import java.util.UUID;

public record AdminReportDto(Long id, UUID videoId, String videoSlug, String videoTitle, VideoStatus videoStatus,
                              String reporterUsername, String reason, String detail, ReportStatus status,
                              Instant createdAt) {

    public static AdminReportDto from(Report report) {
        Video video = report.getVideo();
        return new AdminReportDto(report.getId(), video.getId(), video.getSlug(), video.getTitle(),
                video.getStatus(), report.getReporter().getUsername(), report.getReason(), report.getDetail(),
                report.getStatus(), report.getCreatedAt());
    }
}
