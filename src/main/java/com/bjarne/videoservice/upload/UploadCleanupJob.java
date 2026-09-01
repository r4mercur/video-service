package com.bjarne.videoservice.upload;

import com.bjarne.videoservice.catalog.Video;
import com.bjarne.videoservice.catalog.VideoRepository;
import com.bjarne.videoservice.catalog.VideoStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final Counter abortedCounter;

    public UploadCleanupJob(UploadSessionRepository uploadSessionRepository, VideoRepository videoRepository,
                             S3MultipartClient s3MultipartClient, Clock clock, MeterRegistry meterRegistry) {
        this.uploadSessionRepository = uploadSessionRepository;
        this.videoRepository = videoRepository;
        this.s3MultipartClient = s3MultipartClient;
        this.clock = clock;
        // A rising abort rate means users start uploads that never finish - the first symptom
        // of broken presigned URLs or bucket CORS (CLAUDE.md 9.1/9.3).
        this.abortedCounter = Counter.builder("videoservice.uploads.aborted")
                .description("Orphaned upload sessions aborted after their TTL expired")
                .register(meterRegistry);
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

            abortedCounter.increment();
            log.info("Aborted orphaned upload session {} for video {}", session.getId(), video.getId());
        }
    }
}
