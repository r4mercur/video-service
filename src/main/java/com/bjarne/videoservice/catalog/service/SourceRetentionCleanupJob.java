package com.bjarne.videoservice.catalog.service;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.catalog.storage.StoragePrefixMover;
import com.bjarne.videoservice.config.TranscodeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Deletes the original uploaded source file once it has outlived its retention window
 * (CLAUDE.md 9.2: covers the window where transcode defects typically surface, without keeping
 * the source forever - that would roughly double storage for a capability nobody uses past it).
 * Only relevant when @EnableScheduling is active, i.e. in the "worker" profile (see WorkerConfig,
 * same as UploadCleanupJob, which this mirrors: one @Scheduled + @Transactional method covering
 * the whole batch - deleteAll() is idempotent, so a mid-batch failure just gets retried, whole or
 * in part, on the next tick instead of leaving anything half-done).
 */
@Component
public class SourceRetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SourceRetentionCleanupJob.class);

    private final VideoRepository videoRepository;
    private final StoragePrefixMover storagePrefixMover;
    private final TranscodeProperties properties;
    private final Clock clock;

    public SourceRetentionCleanupJob(VideoRepository videoRepository, StoragePrefixMover storagePrefixMover,
                                      TranscodeProperties properties, Clock clock) {
        this.videoRepository = videoRepository;
        this.storagePrefixMover = storagePrefixMover;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void deleteExpiredSources() {
        Instant cutoff = clock.instant().minus(properties.sourceRetention());
        List<Video> expired = videoRepository.findBySourceKeyIsNotNullAndSourceDeletedAtIsNullAndCreatedAtBefore(cutoff);
        for (Video video : expired) {
            storagePrefixMover.deleteAll("source/" + video.getId());
            video.setSourceDeletedAt(clock.instant());
            videoRepository.save(video);
            log.info("Deleted retention-expired source for video {}", video.getId());
        }
    }
}
