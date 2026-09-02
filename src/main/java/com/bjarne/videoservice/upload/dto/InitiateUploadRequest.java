package com.bjarne.videoservice.upload.dto;

import com.bjarne.videoservice.catalog.entity.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record InitiateUploadRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @NotNull Long categoryId,
        @NotNull Visibility visibility,
        @NotBlank String filename,
        @Positive long sizeBytes,
        @NotBlank String contentType) {
}
