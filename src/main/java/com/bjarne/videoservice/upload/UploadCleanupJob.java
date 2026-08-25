package com.bjarne.videoservice.upload;

import com.bjarne.videoservice.catalog.Video;
import com.bjarne.videoservice.catalog.VideoRepository;
import com.bjarne.videoservice.catalog.VideoStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Aborts orphaned multipart uploads (CLAUDE.md 9.1: AbortMultipartUpload after 24h).
 * Only relevant when @EnableScheduling is active, i.e. in the "worker" profile (see WorkerConfig).
 */
@Component
public class UploadCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(UploadCleanupJob.class);

    private final UploadSessionRepository uploadSessionRepository;
    private final VideoRepository videoRepository;
    private final S3MultipartClient s3MultipartClient;
    private final Clock clock;

    public UploadCleanupJob(UploadSessionRepository uploadSessionRepository, VideoRepository videoRepository,
                             S3MultipartClient s3MultipartClient, Clock clock) {
        this.uploadSessionRepository = uploadSessionRepository;
        this.videoRepository = videoRepository;
        this.s3MultipartClient = s3MultipartClient;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void abortExpiredSessions() {
        List<UploadSession> expired = uploadSessionRepository
                .findByExpiresAtBeforeAndCompletedAtIsNull(clock.instant());
        for (UploadSession session : expired) {
            s3MultipartClient.abortMultipartUpload(session.getS3Key(), session.getS3UploadId());

            Video video = session.getVideo();
            video.setStatus(VideoStatus.FAILED);
            videoRepository.save(video);

            session.setCompletedAt(clock.instant());
            uploadSessionRepository.save(session);

            log.info("Aborted orphaned upload session {} for video {}", session.getId(), video.getId());
        }
    }
}
