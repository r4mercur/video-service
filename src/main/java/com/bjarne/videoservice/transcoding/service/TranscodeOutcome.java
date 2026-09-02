package com.bjarne.videoservice.transcoding.service;

import java.util.List;

public record TranscodeOutcome(
        MediaInfo sourceInfo,
        List<PackagedRendition> renditions,
        boolean hasThumbnail,
        boolean hasSprite) {
}
