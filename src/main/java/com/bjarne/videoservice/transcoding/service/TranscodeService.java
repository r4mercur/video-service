package com.bjarne.videoservice.transcoding.service;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.config.TranscodeProperties;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.bjarne.videoservice.transcoding.storage.ArtifactStorage;
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
 * Orchestrates the actual processing of a job (download, ffprobe, encoding, upload) -
 * deliberately without @Transactional: depending on video length the work can take a long time,
 * and an open DB transaction/connection would be wrong here. The authoritative state transitions
 * (PENDING/RUNNING/DONE/FAILED) are made exclusively by {@link TranscodeJobLifecycle}, called by
 * {@link JobPoller} after process(). The only exception: the frequent, non-critical progress
 * updates during the run are written by process() itself via {@link TranscodeProgressReporter}
 * (status endpoint extension for progressPercent/currentStep) - deliberately not calling
 * TranscodeJobLifecycle for this, to keep its responsibility limited to the state transitions.
 */
@Service
public class TranscodeService {

    private static final Logger log = LoggerFactory.getLogger(TranscodeService.class);

    /**
     * Progress budget in percentage points per pipeline stage. The renditions get the large
     * remainder, weighted by pixel count (a rough but sufficient approximation for x264 encoding
     * cost at a fixed preset/CRF - CLAUDE.md 9.2); stream-copy renditions get a small fixed
     * weight, since remuxing barely costs any time.
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

    public TranscodeService(VideoRepository videoRepository,
            ArtifactStorage artifactStorage,
            MediaProbe mediaProbe,
            RenditionPlanner renditionPlanner,
            HlsPackager hlsPackager,
            TranscodeProperties properties,
            TranscodeProgressReporter progressReporter
    ) {
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
            throw new MediaValidationException("Video has no source_key: " + videoId);
        }
        String storagePrefix = video.getStoragePrefix();

        Path jobDir = properties.workDirPath().resolve(videoId.toString());
        try {
            Files.createDirectories(jobDir);
            Path sourceFile = jobDir.resolve("source" + extension(sourceKey));
            artifactStorage.downloadObject(sourceKey, sourceFile);

            MediaInfo info = mediaProbe.probe(sourceFile);
            mediaProbe.validate(info);
            reportProgress(jobId, PROBE_PERCENT, "Analyzing source file");

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
                    "Sprite sheet");
            Path sprite = hlsPackager.createSpriteSheet(sourceFile, info, outputDir);

            reportProgress(jobId, 100 - UPLOAD_PERCENT, "Uploading");
            artifactStorage.uploadDirectory(outputDir, storagePrefix);
            reportProgress(jobId, 100, "Done");

            return new TranscodeOutcome(info, renditions, thumbnail != null, sprite != null);
        } catch (IOException e) {
            throw new TranscodeProcessException("IO error while processing video " + videoId, e);
        } finally {
            deleteRecursively(jobDir);
        }
    }

    /**
     * Translates the progress within a single rendition (0.0-1.0) into the overall job
     * progress, weighted by pixel count across all planned renditions.
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
                    log.warn("Could not delete temp file: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean up temp directory: {}", directory, e);
        }
    }
}
