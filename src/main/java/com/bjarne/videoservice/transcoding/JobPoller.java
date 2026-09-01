package com.bjarne.videoservice.transcoding;

import com.bjarne.videoservice.catalog.VisibilityMigrationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure scheduler/orchestrator - all DB transitions go through {@link TranscodeJobLifecycle},
 * the actual work through {@link TranscodeService}. Worker concurrency=1 (CLAUDE.md 9.2)
 * follows from the fact that at most one job is processed per tick and the next tick only
 * starts after this method returns - no thread pool needed. Active only in the worker profile
 * (WorkerConfig only enables @EnableScheduling there).
 *
 * Job duration/outcome metrics live here (not in JobQueueMetrics, which is DB-backed) and are
 * therefore emitted by the process actually running the worker - once the worker is split into
 * its own container (CLAUDE.md 3.1), that container needs its own Prometheus scrape target for
 * these to remain visible.
 */
@Component
public class JobPoller {

    private static final Logger log = LoggerFactory.getLogger(JobPoller.class);

    private final TranscodeJobLifecycle lifecycle;
    private final TranscodeService transcodeService;
    private final VisibilityMigrationService visibilityMigrationService;
    private final MeterRegistry meterRegistry;
    private final String workerId;

    public JobPoller(TranscodeJobLifecycle lifecycle, TranscodeService transcodeService,
                      VisibilityMigrationService visibilityMigrationService, MeterRegistry meterRegistry) {
        this.lifecycle = lifecycle;
        this.transcodeService = transcodeService;
        this.visibilityMigrationService = visibilityMigrationService;
        this.meterRegistry = meterRegistry;
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

        if (job.type() == JobType.VISIBILITY_MIGRATION) {
            processMigration(job);
        } else {
            processTranscode(job);
        }
    }

    private void processTranscode(ClaimedJob job) {
        long start = System.nanoTime();
        try {
            TranscodeOutcome outcome = transcodeService.process(job.videoId(), job.jobId());
            lifecycle.recordSuccess(job.jobId(), job.videoId(), outcome);
            recordProcessed(job, start, "success");
            log.info("Job {} for video {} completed successfully", job.jobId(), job.videoId());
        } catch (MediaValidationException e) {
            log.warn("Job {} for video {} failed permanently (validation): {}",
                    job.jobId(), job.videoId(), e.getMessage());
            lifecycle.recordValidationFailure(job.jobId(), job.videoId(), e.getMessage());
            recordProcessed(job, start, "failed_validation");
        } catch (Exception e) {
            log.error("Job {} for video {} failed, may be retried", job.jobId(), job.videoId(), e);
            lifecycle.recordTransientFailure(job.jobId(), job.videoId(), String.valueOf(e.getMessage()));
            recordProcessed(job, start, "failed_transient");
        }
    }

    private void processMigration(ClaimedJob job) {
        long start = System.nanoTime();
        try {
            String newPrefix = visibilityMigrationService.migrate(job.videoId(), job.jobId());
            lifecycle.recordMigrationSuccess(job.jobId(), job.videoId(), newPrefix);
            recordProcessed(job, start, "success");
            log.info("Migration job {} for video {} completed successfully", job.jobId(), job.videoId());
        } catch (Exception e) {
            log.error("Migration job {} for video {} failed, may be retried", job.jobId(), job.videoId(), e);
            lifecycle.recordMigrationTransientFailure(job.jobId(), String.valueOf(e.getMessage()));
            recordProcessed(job, start, "failed_transient");
        }
    }

    private void recordProcessed(ClaimedJob job, long startNanos, String outcome) {
        String type = job.type().name().toLowerCase();
        Counter.builder("videoservice.jobs.processed")
                .tag("type", type)
                .tag("outcome", outcome)
                .description("Finished job attempts by outcome (a retried job counts once per attempt)")
                .register(meterRegistry)
                .increment();
        Timer.builder("videoservice.job.duration")
                .tag("type", type)
                .description("Wall-clock duration of one job attempt, including failed ones")
                .register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "worker";
        }
    }
}
