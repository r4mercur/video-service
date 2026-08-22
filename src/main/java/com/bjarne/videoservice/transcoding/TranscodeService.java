package com.bjarne.videoservice.transcoding;

import com.bjarne.videoservice.catalog.Video;
import com.bjarne.videoservice.catalog.VideoRepository;
import com.bjarne.videoservice.config.TranscodeProperties;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Orchestriert die eigentliche Verarbeitung eines Jobs (Download, ffprobe, Encoding, Upload) -
 * bewusst ohne @Transactional: die Arbeit kann je nach Videolaenge lange dauern, eine offene
 * DB-Transaktion/Connection waere hier falsch. DB-Zustandsaenderungen macht ausschliesslich
 * {@link TranscodeJobLifecycle}, aufgerufen vom {@link JobPoller} nach process().
 */
@Service
public class TranscodeService {

    private static final Logger log = LoggerFactory.getLogger(TranscodeService.class);

    private final VideoRepository videoRepository;
    private final ArtifactStorage artifactStorage;
    private final MediaProbe mediaProbe;
    private final RenditionPlanner renditionPlanner;
    private final HlsPackager hlsPackager;
    private final TranscodeProperties properties;

    public TranscodeService(VideoRepository videoRepository, ArtifactStorage artifactStorage, MediaProbe mediaProbe,
                             RenditionPlanner renditionPlanner, HlsPackager hlsPackager, TranscodeProperties properties) {
        this.videoRepository = videoRepository;
        this.artifactStorage = artifactStorage;
        this.mediaProbe = mediaProbe;
        this.renditionPlanner = renditionPlanner;
        this.hlsPackager = hlsPackager;
        this.properties = properties;
    }

    public TranscodeOutcome process(UUID videoId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new NotFoundException("Video not found: " + videoId));
        String sourceKey = video.getSourceKey();
        if (sourceKey == null) {
            throw new MediaValidationException("Video hat keinen source_key: " + videoId);
        }
        String storagePrefix = video.getStoragePrefix();

        Path jobDir = properties.workDirPath().resolve(videoId.toString());
        try {
            Files.createDirectories(jobDir);
            Path sourceFile = jobDir.resolve("source" + extension(sourceKey));
            artifactStorage.downloadObject(sourceKey, sourceFile);

            MediaInfo info = mediaProbe.probe(sourceFile);
            mediaProbe.validate(info);

            List<RenditionPlanner.PlannedRendition> plan = renditionPlanner.plan(info);
            Path outputDir = jobDir.resolve("output");
            Files.createDirectories(outputDir);

            List<PackagedRendition> renditions = hlsPackager.createRenditions(sourceFile, info, plan, outputDir);
            hlsPackager.writeMasterPlaylist(outputDir, renditions);
            Path thumbnail = hlsPackager.createThumbnail(sourceFile, info, outputDir);
            Path sprite = hlsPackager.createSpriteSheet(sourceFile, info, outputDir);

            artifactStorage.uploadDirectory(outputDir, storagePrefix);

            return new TranscodeOutcome(info, renditions, thumbnail != null, sprite != null);
        } catch (IOException e) {
            throw new TranscodeProcessException("IO-Fehler beim Verarbeiten von Video " + videoId, e);
        } finally {
            deleteRecursively(jobDir);
        }
    }

    private String extension(String key) {
        int dot = key.lastIndexOf('.');
        return dot >= 0 ? key.substring(dot) : "";
    }

    private void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    log.warn("Konnte Temp-Datei nicht loeschen: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("Konnte Temp-Verzeichnis nicht aufraeumen: {}", directory, e);
        }
    }
}
