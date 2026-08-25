package com.bjarne.videoservice.transcoding;

import com.bjarne.videoservice.catalog.*;
import com.bjarne.videoservice.identity.User;
import com.bjarne.videoservice.identity.UserRepository;
import com.bjarne.videoservice.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscodeJobLifecycleTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TranscodeJobLifecycle lifecycle;

    @Autowired
    private TranscodeJobRepository jobRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private VideoRenditionRepository videoRenditionRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Test
    void claimNextClaimsOldestPendingAndMarksRunning() {
        Video video = seedVideo();
        TranscodeJob job = jobRepository.save(new TranscodeJob(video, clock.instant().minusSeconds(5)));

        Optional<ClaimedJob> claimed = lifecycle.claimNext("worker-1");

        assertThat(claimed).isPresent();
        assertThat(claimed.get().jobId()).isEqualTo(job.getId());
        assertThat(claimed.get().videoId()).isEqualTo(video.getId());

        TranscodeJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getLockedBy()).isEqualTo("worker-1");
    }

    @Test
    void claimNextReturnsEmptyWhenNothingPending() {
        assertThat(lifecycle.claimNext("worker-1")).isEmpty();
    }

    @Test
    void claimNextSkipsRowLockedByAnotherOpenTransaction() throws Exception {
        Video videoA = seedVideo();
        Video videoB = seedVideo();
        jobRepository.save(new TranscodeJob(videoA, clock.instant().minusSeconds(10)));
        jobRepository.save(new TranscodeJob(videoB, clock.instant().minusSeconds(5)));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        Future<UUID> holderFuture = executor.submit(() -> {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            return template.execute(status -> {
                UUID lockedVideoId = jobRepository
                        .findClaimable(JobStatus.PENDING, clock.instant(), org.springframework.data.domain.PageRequest.of(0, 1))
                        .get(0).getVideo().getId();
                holderReady.countDown();
                try {
                    releaseHolder.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return lockedVideoId;
            });
        });

        assertThat(holderReady.await(5, TimeUnit.SECONDS)).isTrue();
        Optional<ClaimedJob> claimedByOther = lifecycle.claimNext("worker-2");
        releaseHolder.countDown();
        UUID lockedVideoId = holderFuture.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(claimedByOther).isPresent();
        assertThat(lockedVideoId).isEqualTo(videoA.getId());
        assertThat(claimedByOther.get().videoId()).isNotEqualTo(lockedVideoId);
        assertThat(claimedByOther.get().videoId()).isEqualTo(videoB.getId());
    }

    @Test
    void reclaimStaleRequeuesRunningJobPastTimeout() {
        Video video = seedVideo();
        TranscodeJob job = new TranscodeJob(video, clock.instant());
        job.setStatus(JobStatus.RUNNING);
        job.setLockedAt(clock.instant().minus(3, ChronoUnit.HOURS));
        job.setLockedBy("dead-worker");
        job.setAttempts(1);
        jobRepository.save(job);

        lifecycle.reclaimStale();

        TranscodeJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(reloaded.getLockedBy()).isNull();

        Video reloadedVideo = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(reloadedVideo.getStatus()).isNotEqualTo(VideoStatus.FAILED);
    }

    @Test
    void reclaimStaleMarksFailedWhenAttemptsExhausted() {
        Video video = seedVideo();
        TranscodeJob job = new TranscodeJob(video, clock.instant());
        job.setStatus(JobStatus.RUNNING);
        job.setLockedAt(clock.instant().minus(3, ChronoUnit.HOURS));
        job.setAttempts(3);
        job.setMaxAttempts(3);
        jobRepository.save(job);

        lifecycle.reclaimStale();

        TranscodeJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);

        Video reloadedVideo = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(reloadedVideo.getStatus()).isEqualTo(VideoStatus.FAILED);
    }

    @Test
    void recordSuccessUpdatesVideoAndInsertsRenditions() {
        Video video = seedVideo();
        TranscodeJob job = jobRepository.save(new TranscodeJob(video, clock.instant()));
        MediaInfo info = new MediaInfo(true, true, 42.0, 1280, 720, "h264", "High", "aac");
        PackagedRendition rendition = new PackagedRendition(720, 1280, 2500,
                java.nio.file.Path.of("irrelevant"), 12345L);
        TranscodeOutcome outcome = new TranscodeOutcome(info, List.of(rendition), true, true);

        lifecycle.recordSuccess(job.getId(), video.getId(), outcome);

        Video reloaded = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(VideoStatus.READY);
        assertThat(reloaded.getWidth()).isEqualTo(1280);
        assertThat(reloaded.getHeight()).isEqualTo(720);
        assertThat(reloaded.getDurationSeconds()).isEqualTo(42);
        assertThat(reloaded.getPlaylistKey()).isEqualTo(reloaded.getStoragePrefix() + "/master.m3u8");
        assertThat(reloaded.getThumbnailKey()).isNotNull();
        assertThat(reloaded.getSpriteSheetKey()).isNotNull();
        assertThat(reloaded.getPublishedAt()).isNotNull();

        assertThat(videoRenditionRepository.findAll()).anySatisfy(r -> assertThat(r.getHeight()).isEqualTo(720));

        TranscodeJob reloadedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloadedJob.getStatus()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void recordValidationFailureFailsImmediatelyRegardlessOfAttempts() {
        Video video = seedVideo();
        TranscodeJob job = new TranscodeJob(video, clock.instant());
        job.setAttempts(1);
        job.setMaxAttempts(3);
        jobRepository.save(job);

        lifecycle.recordValidationFailure(job.getId(), video.getId(), "no video track");

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(videoRepository.findById(video.getId()).orElseThrow().getStatus()).isEqualTo(VideoStatus.FAILED);
    }

    @Test
    void recordTransientFailureReschedulesWithBackoffWhenAttemptsRemain() {
        Video video = seedVideo();
        TranscodeJob job = new TranscodeJob(video, clock.instant());
        job.setAttempts(1);
        job.setMaxAttempts(3);
        jobRepository.save(job);

        lifecycle.recordTransientFailure(job.getId(), video.getId(), "ffmpeg crashed");

        TranscodeJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(reloaded.getScheduledAt()).isAfter(clock.instant());
        assertThat(videoRepository.findById(video.getId()).orElseThrow().getStatus())
                .isNotEqualTo(VideoStatus.FAILED);
    }

    @Test
    void recordTransientFailureFailsVideoWhenAttemptsExhausted() {
        Video video = seedVideo();
        TranscodeJob job = new TranscodeJob(video, clock.instant());
        job.setAttempts(3);
        job.setMaxAttempts(3);
        jobRepository.save(job);

        lifecycle.recordTransientFailure(job.getId(), video.getId(), "ffmpeg crashed");

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(videoRepository.findById(video.getId()).orElseThrow().getStatus()).isEqualTo(VideoStatus.FAILED);
    }

    @Test
    void requeueForRetranscodeResetsVideoAndInsertsFreshPendingJob() {
        Video video = seedVideo();
        TranscodeJob oldJob = jobRepository.save(new TranscodeJob(video, clock.instant()));
        oldJob.setStatus(JobStatus.DONE);
        jobRepository.save(oldJob);
        video.setStatus(VideoStatus.READY);
        videoRepository.save(video);
        videoRenditionRepository.save(new com.bjarne.videoservice.catalog.VideoRendition(video, 720, 2500,
                video.getStoragePrefix() + "/720p/playlist.m3u8", 12345L));

        lifecycle.requeueForRetranscode(video.getId());

        Video reloaded = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(VideoStatus.PROCESSING);
        assertThat(videoRenditionRepository.findByVideoId(video.getId())).isEmpty();

        List<TranscodeJob> jobs = jobRepository.findAll().stream()
                .filter(j -> j.getVideo().getId().equals(video.getId())).toList();
        assertThat(jobs).hasSize(2);
        assertThat(jobs).anySatisfy(j -> assertThat(j.getStatus()).isEqualTo(JobStatus.PENDING));

        // Cleanup: otherwise claimNextReturnsEmptyWhenNothingPending would see the PENDING job
        // deliberately left open here, since this shared DB isn't rolled back between tests
        // (see AbstractPostgresIntegrationTest).
        jobRepository.deleteAll(jobs);
    }

    @Test
    void requeueForRetranscodeRejectsWhenJobAlreadyPending() {
        Video video = seedVideo();
        TranscodeJob pendingJob = jobRepository.save(new TranscodeJob(video, clock.instant()));

        assertThatThrownBy(() -> lifecycle.requeueForRetranscode(video.getId()))
                .isInstanceOf(com.bjarne.videoservice.shared.exceptions.ConflictException.class);

        // Cleanup, see comment above.
        jobRepository.delete(pendingJob);
    }

    @Test
    void requeueForRetranscodeRejectsWhenUploadNeverCompleted() {
        User user = userRepository.save(new User("no-source-" + UUID.randomUUID() + "@example.com",
                "no-source-" + UUID.randomUUID(), "irrelevant-hash"));
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();
        Video video = new Video(user, category, "No Source", "no-source-" + UUID.randomUUID(), Visibility.PUBLIC);
        videoRepository.save(video);

        assertThatThrownBy(() -> lifecycle.requeueForRetranscode(video.getId()))
                .isInstanceOf(com.bjarne.videoservice.shared.exceptions.ConflictException.class);
    }

    private Video seedVideo() {
        User user = userRepository.save(new User("lifecycle-" + UUID.randomUUID() + "@example.com",
                "lifecycle-" + UUID.randomUUID(), "irrelevant-hash"));
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();
        Video video = new Video(user, category, "Lifecycle Test", "lifecycle-" + UUID.randomUUID(), Visibility.PUBLIC);
        videoRepository.save(video);
        video.setStoragePrefix("public/" + video.getId());
        video.setSourceKey(video.getStoragePrefix() + "/source.mp4");
        return videoRepository.save(video);
    }
}
