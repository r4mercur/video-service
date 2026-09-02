package com.bjarne.videoservice.transcoding.service;

import com.bjarne.videoservice.config.TranscodeProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

/**
 * ffprobe wrapper for the hard validation from CLAUDE.md 9.2: video track present,
 * duration <= 2h, resolution plausible. probe() and validate() are deliberately separated so
 * the validation rules are unit-testable without an actual ffprobe run.
 */
@Component
public class MediaProbe {

    private static final double MAX_DURATION_SECONDS = 2 * 3600;
    private static final int MIN_DIMENSION = 16;
    private static final int MAX_WIDTH = 7680;
    private static final int MAX_HEIGHT = 4320;

    private final FfmpegRunner ffmpegRunner;
    private final TranscodeProperties properties;
    private final ObjectMapper objectMapper;

    public MediaProbe(FfmpegRunner ffmpegRunner, TranscodeProperties properties, ObjectMapper objectMapper) {
        this.ffmpegRunner = ffmpegRunner;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public MediaInfo probe(Path file) {
        List<String> command = List.of(properties.ffprobePath(), "-v", "error", "-print_format", "json",
                "-show_format", "-show_streams", file.toString());
        String output;
        try {
            output = ffmpegRunner.run(command, properties.jobTimeout());
        } catch (TranscodeProcessException e) {
            // ffprobe practically always fails because of the file itself (broken container,
            // 0-byte file, exotic codec) - a retry doesn't help here.
            throw new MediaValidationException("Source file could not be analyzed: " + e.getMessage());
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(output);
        } catch (JacksonException e) {
            throw new MediaValidationException("ffprobe output is not valid JSON: " + e.getMessage());
        }

        JsonNode videoStream = findStream(root, "video");
        JsonNode audioStream = findStream(root, "audio");
        double duration = root.path("format").path("duration").asDouble(0);

        return new MediaInfo(
                videoStream != null,
                audioStream != null,
                duration,
                videoStream != null ? videoStream.path("width").asInt(0) : 0,
                videoStream != null ? videoStream.path("height").asInt(0) : 0,
                videoStream != null ? videoStream.path("codec_name").asString(null) : null,
                videoStream != null ? videoStream.path("profile").asString(null) : null,
                audioStream != null ? audioStream.path("codec_name").asString(null) : null);
    }

    public void validate(MediaInfo info) {
        if (!info.hasVideoStream()) {
            throw new MediaValidationException("No video track found");
        }
        if (info.durationSeconds() <= 0) {
            throw new MediaValidationException("Invalid or missing duration");
        }
        if (info.durationSeconds() > MAX_DURATION_SECONDS) {
            throw new MediaValidationException("Duration exceeds the 2-hour maximum");
        }
        if (info.width() < MIN_DIMENSION || info.height() < MIN_DIMENSION) {
            throw new MediaValidationException("Resolution too small or not determinable");
        }
        if (info.width() > MAX_WIDTH || info.height() > MAX_HEIGHT) {
            throw new MediaValidationException("Resolution implausibly large");
        }
    }

    private JsonNode findStream(JsonNode root, String codecType) {
        for (JsonNode stream : root.path("streams")) {
            if (codecType.equals(stream.path("codec_type").asString(null))) {
                return stream;
            }
        }
        return null;
    }
}
