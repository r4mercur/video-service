package com.bjarne.videoservice.transcoding;

import java.util.List;

public record TranscodeOutcome(
        MediaInfo sourceInfo,
        List<PackagedRendition> renditions,
        boolean hasThumbnail,
        boolean hasSprite) {
}
