package com.bjarne.videoservice.transcoding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure scheduler/orchestrator - all DB transitions go through {@link TranscodeJobLifecycle},
 * the actual work through {@link TranscodeService}. Worker concurrency=1 (CLAUDE.md 9.2)
 * follows from the fact that at most one job is processed per tick and the next tick only
 * starts after this method returns - no thread pool needed. Active only in the worker profile
 * (WorkerConfig only enables @EnableScheduling there).
 */
@Component
public class JobPoller {

    private static final Logger log = LoggerFactory.getLogger(JobPoller.class);

    private final TranscodeJobLifecycle lifecycle;
    private final TranscodeService transcodeService;
    private final String workerId;

    public JobPoller(TranscodeJobLifecycle lifecycle, TranscodeService transcodeService) {
        this.lifecycle = lifecycle;
        this.transcodeService = transcodeService;
        this.workerId = resolveHostname() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${app.transcode.poll-interval:PT5S}")
    public void poll() {
        lifecycle.reclaimStale();

        Optional<ClaimedJob> claimed = lifecycle.claimNext(workerId);
        if (claimed.isEmpty()) {
            return;
        }
        ClaimedJob job = claimed.get();
        log.info("Job {} for video {} claimed by {}", job.jobId(), job.videoId(), workerId);

        try {
            TranscodeOutcome outcome = transcodeService.process(job.videoId(), job.jobId());
            lifecycle.recordSuccess(job.jobId(), job.videoId(), outcome);
            log.info("Job {} for video {} completed successfully", job.jobId(), job.videoId());
        } catch (MediaValidationException e) {
            log.warn("Job {} for video {} failed permanently (validation): {}",
                    job.jobId(), job.videoId(), e.getMessage());
            lifecycle.recordValidationFailure(job.jobId(), job.videoId(), e.getMessage());
        } catch (Exception e) {
            log.error("Job {} for video {} failed, may be retried", job.jobId(), job.videoId(), e);
            lifecycle.recordTransientFailure(job.jobId(), job.videoId(), String.valueOf(e.getMessage()));
        }
    }

    private String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "worker";
        }
    }
}
