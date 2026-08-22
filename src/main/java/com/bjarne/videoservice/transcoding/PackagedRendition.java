package com.bjarne.videoservice.transcoding;

import java.nio.file.Path;

public record PackagedRendition(int height, int width, int bitrateKbps, Path directory, long sizeBytes) {
}
