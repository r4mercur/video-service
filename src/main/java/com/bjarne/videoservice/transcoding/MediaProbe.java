package com.bjarne.videoservice.transcoding;

import com.bjarne.videoservice.config.TranscodeProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

/**
 * ffprobe-Wrapper fuer die harte Validierung aus CLAUDE.md 9.2: Videospur vorhanden,
 * Dauer <= 2h, Auflösung plausibel. probe() und validate() sind bewusst getrennt, damit
 * die Validierungsregeln ohne echten ffprobe-Lauf unit-testbar sind.
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
            // ffprobe scheitert praktisch immer an der Datei selbst (kaputter Container,
            // 0-Byte-Datei, exotischer Codec) - ein Retry hilft hier nicht.
            throw new MediaValidationException("Quelldatei konnte nicht analysiert werden: " + e.getMessage());
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(output);
        } catch (JacksonException e) {
            throw new MediaValidationException("ffprobe-Ausgabe ist kein gueltiges JSON: " + e.getMessage());
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
            throw new MediaValidationException("Keine Videospur gefunden");
        }
        if (info.durationSeconds() <= 0) {
            throw new MediaValidationException("Ungueltige oder fehlende Dauer");
        }
        if (info.durationSeconds() > MAX_DURATION_SECONDS) {
            throw new MediaValidationException("Dauer ueberschreitet das Maximum von 2 Stunden");
        }
        if (info.width() < MIN_DIMENSION || info.height() < MIN_DIMENSION) {
            throw new MediaValidationException("Aufloesung zu klein oder nicht ermittelbar");
        }
        if (info.width() > MAX_WIDTH || info.height() > MAX_HEIGHT) {
            throw new MediaValidationException("Aufloesung unplausibel gross");
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
