package com.bjarne.videoservice.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CompletedPartDto(@Positive int partNumber, @NotBlank String eTag) {
}
