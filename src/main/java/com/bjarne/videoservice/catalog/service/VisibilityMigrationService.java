package com.bjarne.videoservice.catalog.service;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.entity.Visibility;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.catalog.storage.StoragePrefixMover;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.bjarne.videoservice.transcoding.service.TranscodeProgressReporter;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Does the actual, long-running S3 move for a VISIBILITY_MIGRATION job (CLAUDE.md 9.5) -
 * deliberately not @Transactional, mirroring TranscodeService: the authoritative state
 * transitions are made exclusively by TranscodeJobLifecycle, called by JobPoller after
 * migrate() returns.
 */
@Service
public class VisibilityMigrationService {

    private final VideoRepository videoRepository;
    private final StoragePrefixMover storagePrefixMover;
    private final TranscodeProgressReporter progressReporter;

    public VisibilityMigrationService(VideoRepository videoRepository, StoragePrefixMover storagePrefixMover,
                                       TranscodeProgressReporter progressReporter) {
        this.videoRepository = videoRepository;
        this.storagePrefixMover = storagePrefixMover;
        this.progressReporter = progressReporter;
    }

    public static String prefixFor(Visibility visibility, UUID videoId) {
        return (visibility == Visibility.PUBLIC ? "public/" : "private/") + videoId;
    }

    /**
     * @return the new storage prefix the video's objects now live under
     */
    public String migrate(UUID videoId, Long jobId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new NotFoundException("Video not found: " + videoId));
        String oldPrefix = video.getStoragePrefix();
        String newPrefix = prefixFor(video.getVisibilityTarget(), videoId);

        progressReporter.report(jobId, 0, "Moving storage objects");
        storagePrefixMover.move(oldPrefix, newPrefix);
        progressReporter.report(jobId, 100, "Done");

        return newPrefix;
    }
}
