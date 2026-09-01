package com.bjarne.videoservice.transcoding;

import com.bjarne.videoservice.catalog.VisibilityMigrationService;
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

    @Test
    void dispatchesTranscodeJobToTranscodeService() {
        UUID videoId = UUID.randomUUID();
        ClaimedJob job = new ClaimedJob(1L, videoId, JobType.TRANSCODE);
        when(lifecycle.claimNext(any())).thenReturn(Optional.of(job));
        TranscodeOutcome outcome = new TranscodeOutcome(null, java.util.List.of(), false, false);
        when(transcodeService.process(videoId, 1L)).thenReturn(outcome);

        new JobPoller(lifecycle, transcodeService, visibilityMigrationService).poll();

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

        new JobPoller(lifecycle, transcodeService, visibilityMigrationService).poll();

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

        new JobPoller(lifecycle, transcodeService, visibilityMigrationService).poll();

        verify(lifecycle).recordMigrationTransientFailure(eq(3L), any());
        verify(lifecycle, never()).recordTransientFailure(anyLong(), any(), any());
    }
}
