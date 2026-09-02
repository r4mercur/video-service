package com.bjarne.videoservice.upload.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CompleteUploadRequest(@NotEmpty @Valid List<CompletedPartDto> parts) {
}
