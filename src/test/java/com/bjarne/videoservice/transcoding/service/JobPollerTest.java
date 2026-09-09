package com.bjarne.videoservice.transcoding.service;

import com.bjarne.videoservice.catalog.service.CacheMetadataBackfillService;
import com.bjarne.videoservice.catalog.service.VisibilityMigrationService;
import com.bjarne.videoservice.transcoding.entity.JobType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers the dispatch added for CLAUDE.md 9.5: JobPoller reuses one poll loop for both job
 * types, routing TRANSCODE jobs to TranscodeService/recordSuccess as before, and
 * VISIBILITY_MIGRATION jobs to VisibilityMigrationService/recordMigrationSuccess instead -
 * never both for the same claimed job.
 */
@ExtendWith(MockitoExtension.class)
class JobPollerTest {

    @Mock
    private TranscodeJobLifecycle lifecycle;

    @Mock
    private TranscodeService transcodeService;

    @Mock
    private VisibilityMigrationService visibilityMigrationService;

    @Mock
    private CacheMetadataBackfillService cacheMetadataBackfillService;

    @Test
    void dispatchesTranscodeJobToTranscodeService() {
        UUID videoId = UUID.randomUUID();
        ClaimedJob job = new ClaimedJob(1L, videoId, JobType.TRANSCODE);
        when(lifecycle.claimNext(any())).thenReturn(Optional.of(job));
        TranscodeOutcome outcome = new TranscodeOutcome(null, java.util.List.of(), false, false);
        when(transcodeService.process(videoId, 1L)).thenReturn(outcome);

        new JobPoller(lifecycle, transcodeService, visibilityMigrationService, cacheMetadataBackfillService,
                new SimpleMeterRegistry()).poll();

        verify(transcodeService).process(videoId, 1L);
        verify(lifecycle).recordSuccess(1L, videoId, outcome);
        verifyNoInteractions(visibilityMigrationService);
    }

    @Test
    void dispatchesMigrationJobToVisibilityMigrationService() {
        UUID videoId = UUID.randomUUID();
        ClaimedJob job = new ClaimedJob(2L, videoId, JobType.VISIBILITY_MIGRATION);
        when(lifecycle.claimNext(any())).thenReturn(Optional.of(job));
        when(visibilityMigrationService.migrate(videoId, 2L)).thenReturn("private/" + videoId);

        new JobPoller(lifecycle, transcodeService, visibilityMigrationService, cacheMetadataBackfillService,
                new SimpleMeterRegistry()).poll();

        verify(visibilityMigrationService).migrate(videoId, 2L);
        verify(lifecycle).recordMigrationSuccess(2L, videoId, "private/" + videoId);
        verifyNoInteractions(transcodeService);
    }

    @Test
    void migrationFailureRecordsTransientFailureNotVideoFailure() {
        UUID videoId = UUID.randomUUID();
        ClaimedJob job = new ClaimedJob(3L, videoId, JobType.VISIBILITY_MIGRATION);
        when(lifecycle.claimNext(any())).thenReturn(Optional.of(job));
        when(visibilityMigrationService.migrate(videoId, 3L)).thenThrow(new RuntimeException("S3 copy failed"));

        new JobPoller(lifecycle, transcodeService, visibilityMigrationService, cacheMetadataBackfillService,
                new SimpleMeterRegistry()).poll();

        verify(lifecycle).recordMigrationTransientFailure(eq(3L), any());
        verify(lifecycle, never()).recordTransientFailure(anyLong(), any(), any());
    }

    @Test
    void dispatchesBackfillJobToCacheMetadataBackfillService() {
        UUID videoId = UUID.randomUUID();
        ClaimedJob job = new ClaimedJob(4L, videoId, JobType.CACHE_METADATA_BACKFILL);
        when(lifecycle.claimNext(any())).thenReturn(Optional.of(job));
        when(cacheMetadataBackfillService.backfill(videoId, 4L)).thenReturn(1056);

        new JobPoller(lifecycle, transcodeService, visibilityMigrationService, cacheMetadataBackfillService,
                new SimpleMeterRegistry()).poll();

        verify(cacheMetadataBackfillService).backfill(videoId, 4L);
        verify(lifecycle).recordBackfillSuccess(4L);
        verifyNoInteractions(transcodeService, visibilityMigrationService);
    }

    /**
     * A failed backfill must leave the video alone - its objects still play, they just lack the
     * cache metadata. Marking the video FAILED over a metadata rewrite would take a working
     * video offline.
     */
    @Test
    void backfillFailureLeavesTheVideoUntouched() {
        UUID videoId = UUID.randomUUID();
        ClaimedJob job = new ClaimedJob(5L, videoId, JobType.CACHE_METADATA_BACKFILL);
        when(lifecycle.claimNext(any())).thenReturn(Optional.of(job));
        when(cacheMetadataBackfillService.backfill(videoId, 5L)).thenThrow(new RuntimeException("S3 copy failed"));

        new JobPoller(lifecycle, transcodeService, visibilityMigrationService, cacheMetadataBackfillService,
                new SimpleMeterRegistry()).poll();

        verify(lifecycle).recordBackfillFailure(eq(5L), any());
        verify(lifecycle, never()).recordTransientFailure(anyLong(), any(), any());
    }
}
