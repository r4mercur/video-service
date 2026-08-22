package com.bjarne.videoservice.moderation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitReportRequest(@NotNull ReportReason reason, @Size(max = 2000) String detail) {
}
