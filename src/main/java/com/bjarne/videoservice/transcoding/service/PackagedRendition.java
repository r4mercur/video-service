package com.bjarne.videoservice.transcoding.service;

import java.nio.file.Path;

public record PackagedRendition(int height, int width, int bitrateKbps, Path directory, long sizeBytes) {
}
