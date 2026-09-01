package com.bjarne.videoservice.transcoding;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The gauges are the data source for the queue alerts in prometheus/alerts.yml - especially
 * oldest_pending_age_seconds, whose clamping rules (empty queue, backoff in the future) decide
 * whether TranscodeQueueStalled can ever false-positive.
 */
class JobQueueMetricsTest {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    private final TranscodeJobRepository repository = mock(TranscodeJobRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private JobQueueMetrics metricsAt(Instant now) {
        return new JobQueueMetrics(repository, Clock.fixed(now, ZoneOffset.UTC), registry);
    }

    @Test
    void reportsQueueCountsPerTypeAndStatus() {
        when(repository.countByStatusAndType(JobStatus.PENDING, JobType.TRANSCODE)).thenReturn(3L);
        when(repository.countByStatusAndType(JobStatus.PENDING, JobType.VISIBILITY_MIGRATION)).thenReturn(1L);
        when(repository.countByStatus(JobStatus.RUNNING)).thenReturn(1L);
        when(repository.countByStatus(JobStatus.FAILED)).thenReturn(2L);
        metricsAt(NOW);

        assertThat(registry.get("videoservice.jobs.queued").tag("type", "transcode").gauge().value())
                .isEqualTo(3.0);
        assertThat(registry.get("videoservice.jobs.queued").tag("type", "visibility_migration").gauge().value())
                .isEqualTo(1.0);
        assertThat(registry.get("videoservice.jobs.running").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("videoservice.jobs.failed").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void oldestPendingAgeIsSecondsSinceScheduledAt() {
        when(repository.findOldestScheduledAt(JobStatus.PENDING))
                .thenReturn(Optional.of(NOW.minusSeconds(600)));
        metricsAt(NOW);

        assertThat(registry.get("videoservice.jobs.oldest.pending.age.seconds").gauge().value())
                .isEqualTo(600.0);
    }

    @Test
    void oldestPendingAgeIsZeroForEmptyQueue() {
        when(repository.findOldestScheduledAt(JobStatus.PENDING)).thenReturn(Optional.empty());
        metricsAt(NOW);

        assertThat(registry.get("videoservice.jobs.oldest.pending.age.seconds").gauge().value())
                .isEqualTo(0.0);
    }

    @Test
    void oldestPendingAgeClampsFutureBackoffToZero() {
        // The only PENDING job is a retry backed off into the future - nothing is overdue.
        when(repository.findOldestScheduledAt(JobStatus.PENDING))
                .thenReturn(Optional.of(NOW.plusSeconds(120)));
        metricsAt(NOW);

        assertThat(registry.get("videoservice.jobs.oldest.pending.age.seconds").gauge().value())
                .isEqualTo(0.0);
    }
}
