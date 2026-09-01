package com.bjarne.videoservice.upload;

import com.bjarne.videoservice.catalog.VideoStatus;
import com.bjarne.videoservice.catalog.Visibility;

public record VideoStatusResponse(VideoStatus status, Visibility visibilityTarget, String lastError,
                                   int progressPercent, String currentStep) {
}
