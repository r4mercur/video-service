package com.bjarne.videoservice.moderation.dto;

import com.bjarne.videoservice.moderation.entity.Report;
import com.bjarne.videoservice.moderation.entity.ReportStatus;

public record ReportResponse(Long id, ReportStatus status) {

    public static ReportResponse from(Report report) {
        return new ReportResponse(report.getId(), report.getStatus());
    }
}
