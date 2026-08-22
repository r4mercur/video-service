package com.bjarne.videoservice.moderation;

public record ReportResponse(Long id, ReportStatus status) {

    public static ReportResponse from(Report report) {
        return new ReportResponse(report.getId(), report.getStatus());
    }
}
