package com.bjarne.videoservice.upload;

import com.bjarne.videoservice.catalog.VideoStatus;

public record VideoStatusResponse(VideoStatus status, String lastError) {
}
