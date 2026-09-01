package com.bjarne.videoservice.transcoding;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * DB-backed gauges over the transcode_jobs queue (AP9, CLAUDE.md 10: "queue alerting").
 * Gauges are evaluated lazily at scrape time (every 15s, see prometheus.yml) - a handful of
 * COUNT/MIN queries against the (status, scheduled_at) index from V1, cheap enough to not need
 * caching. Deliberately not profile-gated: the metrics come from the DB, not from this process,
 * so the "api" process (the one Prometheus scrapes, see prometheus.prod.yml) reports the queue
 * state even after the worker is split into its own container (CLAUDE.md 3.1).
 */
@Component
public class JobQueueMetrics {

    private final TranscodeJobRepository jobRepository;
    private final Clock clock;

    public JobQueueMetrics(TranscodeJobRepository jobRepository, Clock clock, MeterRegistry registry) {
        this.jobRepository = jobRepository;
        this.clock = clock;

        for (JobType type : JobType.values()) {
            Gauge.builder("videoservice.jobs.queued", () -> jobRepository.countByStatusAndType(JobStatus.PENDING, type))
                    .tag("type", type.name().toLowerCase())
                    .description("Jobs waiting in the queue (status PENDING)")
                    .register(registry);
        }
        Gauge.builder("videoservice.jobs.running", () -> jobRepository.countByStatus(JobStatus.RUNNING))
                .description("Jobs currently claimed by a worker")
                .register(registry);
        Gauge.builder("videoservice.jobs.failed", () -> jobRepository.countByStatus(JobStatus.FAILED))
                .description("Jobs that permanently failed (retries exhausted or validation failure)")
                .register(registry);
        Gauge.builder("videoservice.jobs.oldest.pending.age.seconds", this::oldestPendingAgeSeconds)
                .description("Age of the oldest PENDING job past its scheduled_at; 0 when the queue is empty "
                        + "or everything is still backed off into the future")
                .register(registry);
    }

    private double oldestPendingAgeSeconds() {
        return jobRepository.findOldestScheduledAt(JobStatus.PENDING)
                // Negative age just means the earliest job is a backoff scheduled in the future -
                // nothing is overdue, so clamp to 0 instead of reporting a misleading negative.
                .map(oldest -> Math.max(0, Duration.between(oldest, clock.instant()).toSeconds()))
                .map(Long::doubleValue)
                .orElse(0.0);
    }
}
