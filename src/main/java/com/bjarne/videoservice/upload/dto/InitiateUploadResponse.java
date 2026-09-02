package com.bjarne.videoservice.upload.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InitiateUploadResponse(
        UUID videoId,
        List<UploadPartUrl> parts,
        long partSizeBytes,
        Instant expiresAt) {
}
