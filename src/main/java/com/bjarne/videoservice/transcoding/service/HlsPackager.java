package com.bjarne.videoservice.transcoding.service;

import com.bjarne.videoservice.config.TranscodeProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Baut aus den geplanten Renditions (siehe {@link RenditionPlanner}) HLS-fMP4-Ausgaben
 * (Segmentlaenge 4s, CLAUDE.md 9.2), Thumbnail und Sprite-Sheet, jeweils per separatem
 * ffmpeg-Aufruf statt eines einzelnen var_stream_map-Kommandos (einfacher zu debuggen).
 */
@Component
public class HlsPackager {

    private static final Map<Integer, Integer> BASELINE_BITRATE_KBPS = Map.of(360, 800, 720, 2500, 1080, 4500);

    private final FfmpegRunner ffmpegRunner;
    private final TranscodeProperties properties;

    public HlsPackager(FfmpegRunner ffmpegRunner, TranscodeProperties properties) {
        this.ffmpegRunner = ffmpegRunner;
        this.properties = properties;
    }

    /**
     * @param progressListener meldet je Rendition (Index in {@code plan}) den Fortschritt innerhalb
     *                          dieser Rendition als Bruchteil 0.0-1.0, abgeleitet aus ffmpegs
     *                          "-progress"-Ausgabe relativ zur bekannten Quelldauer.
     */
    public List<PackagedRendition> createRenditions(Path sourceFile, MediaInfo sourceInfo,
                                                      List<RenditionPlanner.PlannedRendition> plan, Path outputDir,
                                                      BiConsumer<Integer, Double> progressListener) {
        List<PackagedRendition> result = new ArrayList<>(plan.size());
        for (int renditionIndex = 0; renditionIndex < plan.size(); renditionIndex++) {
            RenditionPlanner.PlannedRendition rendition = plan.get(renditionIndex);
            Path renditionDir = outputDir.resolve(rendition.height() + "p");
            createDirectory(renditionDir);

            List<String> command = new ArrayList<>();
            command.add(properties.ffmpegPath());
            command.add("-y");
            command.add("-i");
            command.add(sourceFile.toString());
            command.add("-map");
            command.add("0:v:0");
            if (sourceInfo.hasAudioStream()) {
                command.add("-map");
                command.add("0:a:0");
            }
            if (rendition.streamCopy()) {
                command.add("-c");
                command.add("copy");
            } else {
                command.add("-c:v");
                command.add("libx264");
                command.add("-profile:v");
                command.add("high");
                command.add("-preset");
                command.add("veryfast");
                command.add("-crf");
                command.add("23");
                command.add("-vf");
                command.add("scale=-2:" + rendition.height());
                if (sourceInfo.hasAudioStream()) {
                    command.add("-c:a");
                    command.add("aac");
                    command.add("-b:a");
                    command.add("128k");
                }
            }
            command.add("-f");
            command.add("hls");
            command.add("-hls_time");
            command.add("4");
            command.add("-hls_playlist_type");
            command.add("vod");
            command.add("-hls_segment_type");
            command.add("fmp4");
            command.add("-hls_fmp4_init_filename");
            command.add("init.mp4");
            command.add("-hls_segment_filename");
            command.add(renditionDir.resolve("segment_%03d.m4s").toString());
            command.add(renditionDir.resolve("playlist.m3u8").toString());

            int index = renditionIndex;
            if (sourceInfo.durationSeconds() > 0) {
                ffmpegRunner.run(command, properties.jobTimeout(), renditionDir, outTime -> progressListener.accept(index,
                        clampFraction(outTime.toMillis() / (sourceInfo.durationSeconds() * 1000))));
            } else {
                ffmpegRunner.run(command, properties.jobTimeout(), renditionDir);
            }
            progressListener.accept(index, 1.0);

            int width = rendition.streamCopy() ? sourceInfo.width() : scaledWidth(sourceInfo, rendition.height());
            int bitrateKbps = bitrateForHeight(rendition.height());
            long sizeBytes = directorySize(renditionDir);
            result.add(new PackagedRendition(rendition.height(), width, bitrateKbps, renditionDir, sizeBytes));
        }
        return result;
    }

    public void writeMasterPlaylist(Path outputDir, List<PackagedRendition> renditions) {
        StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n");
        renditions.stream()
                .sorted((a, b) -> Integer.compare(a.height(), b.height()))
                .forEach(rendition -> playlist
                        .append("#EXT-X-STREAM-INF:BANDWIDTH=").append(rendition.bitrateKbps() * 1000)
                        .append(",RESOLUTION=").append(rendition.width()).append('x').append(rendition.height())
                        .append('\n')
                        .append(rendition.height()).append("p/playlist.m3u8\n"));
        writeString(outputDir.resolve("master.m3u8"), playlist.toString());
    }

    public Path createThumbnail(Path sourceFile, MediaInfo sourceInfo, Path outputDir) {
        double timestamp = Math.min(sourceInfo.durationSeconds() * 0.1, Math.max(sourceInfo.durationSeconds() - 0.1, 0));
        Path thumbnail = outputDir.resolve("thumbnail.jpg");
        ffmpegRunner.run(List.of(
                properties.ffmpegPath(), "-y",
                "-ss", String.valueOf(timestamp),
                "-i", sourceFile.toString(),
                "-frames:v", "1",
                "-vf", "scale=640:-2",
                thumbnail.toString()
        ), properties.jobTimeout());
        return thumbnail;
    }

    public Path createSpriteSheet(Path sourceFile, MediaInfo sourceInfo, Path outputDir) {
        int interval = properties.spriteIntervalSeconds();
        if (sourceInfo.durationSeconds() < interval * 2.0) {
            return null;
        }
        int columns = properties.spriteColumns();
        int tileCount = (int) Math.ceil(sourceInfo.durationSeconds() / interval);
        int rows = (int) Math.ceil((double) tileCount / columns);

        Path sprite = outputDir.resolve("sprite.jpg");
        String filter = "fps=1/%d,scale=%d:%d,tile=%dx%d".formatted(
                interval, properties.spriteTileWidth(), properties.spriteTileHeight(), columns, rows);
        ffmpegRunner.run(List.of(
                properties.ffmpegPath(), "-y",
                "-i", sourceFile.toString(),
                "-vf", filter,
                "-frames:v", "1",
                sprite.toString()
        ), properties.jobTimeout());
        return sprite;
    }

    private double clampFraction(double fraction) {
        return Math.min(1.0, Math.max(0.0, fraction));
    }

    private int scaledWidth(MediaInfo sourceInfo, int targetHeight) {
        double raw = (double) sourceInfo.width() * targetHeight / sourceInfo.height();
        long even = Math.round(raw / 2.0) * 2;
        return (int) even;
    }

    private int bitrateForHeight(int height) {
        Integer baseline = BASELINE_BITRATE_KBPS.get(height);
        if (baseline != null) {
            return baseline;
        }
        return Math.max(400, Math.round(800f * height / 360));
    }

    private long directorySize(Path directory) {
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .mapToLong(file -> {
                        try {
                            return Files.size(file);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .sum();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeString(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
