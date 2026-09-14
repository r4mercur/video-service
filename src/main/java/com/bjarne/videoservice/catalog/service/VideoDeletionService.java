package com.bjarne.videoservice.catalog.service;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.catalog.storage.StoragePrefixMover;
import com.bjarne.videoservice.transcoding.service.TranscodeProgressReporter;
import com.bjarne.videoservice.upload.repository.UploadSessionRepository;
import com.bjarne.videoservice.upload.storage.S3MultipartClient;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Does the storage side of a VIDEO_DELETION job (CLAUDE.md 9.7) - deliberately not
 * @Transactional, mirroring {@link VisibilityMigrationService}: it only talks to storage, and the
 * authoritative DB transition (removing the video row) is made by TranscodeJobLifecycle once this
 * returns. Every step is idempotent, so a retry after a partial run - or after the worker died
 * midway - simply finishes the work.
 */
@Service
public class VideoDeletionService {

    private final VideoRepository videoRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final S3MultipartClient s3MultipartClient;
    private final StoragePrefixMover storagePrefixMover;
    private final TranscodeProgressReporter progressReporter;

    public VideoDeletionService(VideoRepository videoRepository,
                                UploadSessionRepository uploadSessionRepository,
                                S3MultipartClient s3MultipartClient,
                                StoragePrefixMover storagePrefixMover,
                                TranscodeProgressReporter progressReporter) {
        this.videoRepository = videoRepository;
        this.uploadSessionRepository = uploadSessionRepository;
        this.s3MultipartClient = s3MultipartClient;
        this.storagePrefixMover = storagePrefixMover;
        this.progressReporter = progressReporter;
    }

    public void deleteStorage(UUID videoId, Long jobId) {
        Optional<Video> found = videoRepository.findById(videoId);
        if (found.isEmpty()) {
            // The row only goes after storage has been emptied, so there is nothing left to remove.
            return;
        }
        Video video = found.get();

        // An upload that never completed has parts but no listable objects, and the session row
        // UploadCleanupJob would abort it from is removed together with the video (ON DELETE CASCADE).
        uploadSessionRepository.findFirstByVideoIdAndCompletedAtIsNullOrderByIdDesc(videoId)
                .ifPresent(session -> s3MultipartClient.abortMultipartUpload(session.getS3Key(), session.getS3UploadId()));

        progressReporter.report(jobId, 10, "Deleting renditions");
        if (video.getStoragePrefix() != null) {
            storagePrefixMover.deleteAll(video.getStoragePrefix());
        }

        // Unconditional, unlike the old synchronous delete: the source lives under its own prefix
        // independent of storagePrefix, and listing an already empty prefix is a single request.
        progressReporter.report(jobId, 80, "Deleting source");
        storagePrefixMover.deleteAll("source/" + videoId);
        progressReporter.report(jobId, 100, "Done");
    }
}
