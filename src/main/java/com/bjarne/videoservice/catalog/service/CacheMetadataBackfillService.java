package com.bjarne.videoservice.catalog.service;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.catalog.storage.StoragePrefixMover;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.bjarne.videoservice.transcoding.service.TranscodeProgressReporter;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Runs a CACHE_METADATA_BACKFILL job: re-stamps Content-Type and Cache-Control onto the storage
 * objects of one video (CLAUDE.md 9.3). Videos uploaded before {@code CachePolicy} existed have
 * no Cache-Control at all, which leaves browsers guessing at freshness - expensive against a slow
 * origin, and unpredictable in a way that makes delivery problems hard to reason about.
 *
 * <p>Deliberately not @Transactional, mirroring {@link VisibilityMigrationService}: the
 * authoritative job state transitions belong to TranscodeJobLifecycle, driven by JobPoller after
 * this returns. Nothing in the database changes here at all - the work is purely in storage, and
 * it is idempotent, so a retry after a partial run simply rewrites the same values.
 */
@Service
public class CacheMetadataBackfillService {

    private final VideoRepository videoRepository;
    private final StoragePrefixMover storagePrefixMover;
    private final TranscodeProgressReporter progressReporter;

    public CacheMetadataBackfillService(VideoRepository videoRepository,
                                        StoragePrefixMover storagePrefixMover,
                                        TranscodeProgressReporter progressReporter) {
        this.videoRepository = videoRepository;
        this.storagePrefixMover = storagePrefixMover;
        this.progressReporter = progressReporter;
    }

    /**
     * @return the number of storage objects whose metadata was rewritten
     */
    public int backfill(UUID videoId, Long jobId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new NotFoundException("Video not found: " + videoId));
        if (video.getStoragePrefix() == null) {
            return 0;
        }

        progressReporter.report(jobId, 0, "Rewriting cache metadata");
        int rewritten = storagePrefixMover.refreshObjectMetadata(video.getStoragePrefix());
        progressReporter.report(jobId, 100, "Done");

        return rewritten;
    }
}
