package com.bjarne.videoservice.upload.dto;

import com.bjarne.videoservice.catalog.entity.VideoStatus;
import com.bjarne.videoservice.catalog.entity.Visibility;

public record VideoStatusResponse(VideoStatus status, Visibility visibilityTarget, String lastError,
                                   int progressPercent, String currentStep) {
}
