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
 * DB-Transaktion/Connection waere hier falsch. Die massgeblichen Zustandsuebergaenge (PENDING/
 * RUNNING/DONE/FAILED) macht ausschliesslich {@link TranscodeJobLifecycle}, aufgerufen vom
 * {@link JobPoller} nach process(). Einzige Ausnahme: die haeufigen, unkritischen Fortschritts-
 * Updates waehrend des Laufs schreibt process() selbst ueber {@link TranscodeProgressReporter}
 * (Status-Endpunkt-Erweiterung um progressPercent/currentStep) - bewusst kein Aufruf von
 * TranscodeJobLifecycle dafuer, um dessen Zustaendigkeit auf die Zustandsuebergaenge begrenzt zu
 * halten.
 */
@Service
public class TranscodeService {

    private static final Logger log = LoggerFactory.getLogger(TranscodeService.class);

    /**
     * Fortschritts-Budget in Prozentpunkten je Pipeline-Stufe. Die Renditions bekommen den
     * grossen Rest, gewichtet nach Pixelzahl (grobe, aber ausreichende Naeherung fuer die
     * x264-Encodingkosten bei fixem Preset/CRF - CLAUDE.md 9.2); Stream-Copy-Renditions bekommen
     * ein kleines festes Gewicht, da Remuxen kaum Zeit kostet.
     */
    private static final int PROBE_PERCENT = 3;
    private static final int THUMBNAIL_PERCENT = 3;
    private static final int SPRITE_PERCENT = 3;
    private static final int UPLOAD_PERCENT = 6;
    private static final int RENDITIONS_PERCENT = 100 - PROBE_PERCENT - THUMBNAIL_PERCENT - SPRITE_PERCENT - UPLOAD_PERCENT;
    private static final double STREAM_COPY_WEIGHT = 5.0;

    private final VideoRepository videoRepository;
    private final ArtifactStorage artifactStorage;
    private final MediaProbe mediaProbe;
    private final RenditionPlanner renditionPlanner;
    private final HlsPackager hlsPackager;
    private final TranscodeProperties properties;
    private final TranscodeProgressReporter progressReporter;

    public TranscodeService(VideoRepository videoRepository, ArtifactStorage artifactStorage, MediaProbe mediaProbe,
                             RenditionPlanner renditionPlanner, HlsPackager hlsPackager, TranscodeProperties properties,
                             TranscodeProgressReporter progressReporter) {
        this.videoRepository = videoRepository;
        this.artifactStorage = artifactStorage;
        this.mediaProbe = mediaProbe;
        this.renditionPlanner = renditionPlanner;
        this.hlsPackager = hlsPackager;
        this.properties = properties;
        this.progressReporter = progressReporter;
    }

    public TranscodeOutcome process(UUID videoId) {
        return process(videoId, null);
    }

    public TranscodeOutcome process(UUID videoId, Long jobId) {
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
            reportProgress(jobId, PROBE_PERCENT, "Analysiere Quelldatei");

            List<RenditionPlanner.PlannedRendition> plan = renditionPlanner.plan(info);
            Path outputDir = jobDir.resolve("output");
            Files.createDirectories(outputDir);

            List<PackagedRendition> renditions = hlsPackager.createRenditions(sourceFile, info, plan, outputDir,
                    (renditionIndex, fraction) -> reportProgress(jobId,
                            renditionOverallPercent(plan, renditionIndex, fraction),
                            "Transcoding " + plan.get(renditionIndex).height() + "p"));
            hlsPackager.writeMasterPlaylist(outputDir, renditions);

            reportProgress(jobId, PROBE_PERCENT + RENDITIONS_PERCENT + THUMBNAIL_PERCENT, "Thumbnail");
            Path thumbnail = hlsPackager.createThumbnail(sourceFile, info, outputDir);

            reportProgress(jobId, PROBE_PERCENT + RENDITIONS_PERCENT + THUMBNAIL_PERCENT + SPRITE_PERCENT,
                    "Sprite-Sheet");
            Path sprite = hlsPackager.createSpriteSheet(sourceFile, info, outputDir);

            reportProgress(jobId, 100 - UPLOAD_PERCENT, "Hochladen");
            artifactStorage.uploadDirectory(outputDir, storagePrefix);
            reportProgress(jobId, 100, "Fertig");

            return new TranscodeOutcome(info, renditions, thumbnail != null, sprite != null);
        } catch (IOException e) {
            throw new TranscodeProcessException("IO-Fehler beim Verarbeiten von Video " + videoId, e);
        } finally {
            deleteRecursively(jobDir);
        }
    }

    /**
     * Uebersetzt den Fortschritt innerhalb einer einzelnen Rendition (0.0-1.0) in den
     * Gesamtfortschritt des Jobs, nach Pixelzahl gewichtet ueber alle geplanten Renditions.
     */
    private int renditionOverallPercent(List<RenditionPlanner.PlannedRendition> plan, int renditionIndex,
                                         double fractionWithinRendition) {
        double totalWeight = plan.stream().mapToDouble(this::renditionWeight).sum();
        double weightBefore = plan.subList(0, renditionIndex).stream().mapToDouble(this::renditionWeight).sum();
        double currentWeight = renditionWeight(plan.get(renditionIndex));

        double renditionsProgress = (weightBefore + currentWeight * fractionWithinRendition) / totalWeight;
        return PROBE_PERCENT + (int) Math.round(RENDITIONS_PERCENT * renditionsProgress);
    }

    private double renditionWeight(RenditionPlanner.PlannedRendition rendition) {
        return rendition.streamCopy() ? STREAM_COPY_WEIGHT : (double) rendition.height() * rendition.height();
    }

    private void reportProgress(Long jobId, int percent, String step) {
        if (jobId != null) {
            progressReporter.report(jobId, percent, step);
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
