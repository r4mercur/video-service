package com.bjarne.videoservice.moderation.dto;

import com.bjarne.videoservice.moderation.entity.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitReportRequest(@NotNull ReportReason reason, @Size(max = 2000) String detail) {
}
