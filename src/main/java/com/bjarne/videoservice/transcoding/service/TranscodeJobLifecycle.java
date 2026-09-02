package com.bjarne.videoservice.transcoding.service;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.entity.VideoRendition;
import com.bjarne.videoservice.catalog.entity.VideoStatus;
import com.bjarne.videoservice.catalog.entity.Visibility;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.catalog.storage.StoragePrefixMover;
import com.bjarne.videoservice.config.TranscodeProperties;
import com.bjarne.videoservice.shared.exceptions.ConflictException;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.bjarne.videoservice.transcoding.entity.JobStatus;
import com.bjarne.videoservice.transcoding.entity.JobType;
import com.bjarne.videoservice.transcoding.entity.TranscodeJob;
import com.bjarne.videoservice.transcoding.repository.TranscodeJobRepository;
import com.bjarne.videoservice.transcoding.repository.VideoRenditionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * All DB state transitions for transcode jobs, each in its own short transaction - deliberately
 * separated from {@link TranscodeService} (which does the actual, long-running work outside any
 * transaction) and from {@link JobPoller} (a pure scheduler/orchestrator, so that @Transactional
 * here isn't bypassed by a self-invocation).
 */
@Service
public class TranscodeJobLifecycle {

    private final TranscodeJobRepository jobRepository;
    private final VideoRepository videoRepository;
    private final VideoRenditionRepository videoRenditionRepository;
    private final TranscodeProperties properties;
    private final Clock clock;

    public TranscodeJobLifecycle(TranscodeJobRepository jobRepository, VideoRepository videoRepository,
                                  VideoRenditionRepository videoRenditionRepository, TranscodeProperties properties,
                                  Clock clock) {
        this.jobRepository = jobRepository;
        this.videoRepository = videoRepository;
        this.videoRenditionRepository = videoRenditionRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void reclaimStale() {
        Instant cutoff = clock.instant().minus(properties.staleJobTimeout());
        List<TranscodeJob> stale = jobRepository.findByStatusAndLockedAtBefore(JobStatus.RUNNING, cutoff);
        for (TranscodeJob job : stale) {
            boolean exhausted = requeueOrFail(job,
                    "Worker timeout: job was locked for longer than " + properties.staleJobTimeout());
            // A stale VISIBILITY_MIGRATION job leaves the video exactly as it was - still READY,
            // still served from the old prefix/visibility (see recordMigrationTransientFailure).
            if (exhausted && job.getType() == JobType.TRANSCODE) {
                markVideoFailed(job.getVideo().getId());
            }
        }
    }

    /**
     * Triggers a complete re-transcode for a video that has already been processed (or failed) -
     * e.g. after a bug in the packaging logic was fixed and existing videos with broken artifacts
     * need to be regenerated. Old rendition rows are deleted, since the ladder can differ between
     * runs (RenditionPlanner); {@code recordSuccess} recreates them on the next successful run.
     */
    @Transactional
    public void requeueForRetranscode(UUID videoId) {
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        if (video.getSourceKey() == null) {
            throw new ConflictException("Video does not have a completed upload yet: " + videoId);
        }
        jobRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)
                .filter(job -> job.getStatus() == JobStatus.PENDING || job.getStatus() == JobStatus.RUNNING)
                .ifPresent(job -> {
                    throw new ConflictException("A transcode job is already running for this video: " + videoId);
                });

        videoRenditionRepository.deleteByVideoId(videoId);
        video.setStatus(VideoStatus.PROCESSING);
        videoRepository.save(video);
        jobRepository.save(new TranscodeJob(video, clock.instant()));
    }

    @Transactional
    public Optional<ClaimedJob> claimNext(String workerId) {
        List<TranscodeJob> claimable = jobRepository.findClaimable(JobStatus.PENDING, clock.instant(),
                PageRequest.of(0, 1));
        if (claimable.isEmpty()) {
            return Optional.empty();
        }
        TranscodeJob job = claimable.get(0);
        job.setStatus(JobStatus.RUNNING);
        job.setLockedAt(clock.instant());
        job.setLockedBy(workerId);
        job.setAttempts(job.getAttempts() + 1);
        jobRepository.save(job);
        UUID videoId = job.getVideo().getId();
        return Optional.of(new ClaimedJob(job.getId(), videoId, job.getType()));
    }

    @Transactional
    public void recordSuccess(Long jobId, UUID videoId, TranscodeOutcome outcome) {
        TranscodeJob job = jobRepository.findById(jobId).orElseThrow(() -> new NotFoundException("Job not found"));
        job.setStatus(JobStatus.DONE);
        jobRepository.save(job);

        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        MediaInfo info = outcome.sourceInfo();
        video.setWidth(info.width());
        video.setHeight(info.height());
        video.setDurationSeconds((int) Math.round(info.durationSeconds()));
        video.setPlaylistKey(video.getStoragePrefix() + "/master.m3u8");
        if (outcome.hasThumbnail() && !video.isHasCustomThumbnail()) {
            video.setThumbnailKey(video.getStoragePrefix() + "/thumbnail.jpg");
        }
        if (outcome.hasSprite()) {
            video.setSpriteSheetKey(video.getStoragePrefix() + "/sprite.jpg");
        }
        video.setStatus(VideoStatus.READY);
        video.setPublishedAt(clock.instant());
        videoRepository.save(video);

        for (PackagedRendition rendition : outcome.renditions()) {
            String playlistKey = video.getStoragePrefix() + "/" + rendition.height() + "p/playlist.m3u8";
            videoRenditionRepository.save(new VideoRendition(video, rendition.height(), rendition.bitrateKbps(),
                    playlistKey, rendition.sizeBytes()));
        }
    }

    @Transactional
    public void recordValidationFailure(Long jobId, UUID videoId, String error) {
        TranscodeJob job = jobRepository.findById(jobId).orElseThrow(() -> new NotFoundException("Job not found"));
        job.setStatus(JobStatus.FAILED);
        job.setLastError(error);
        jobRepository.save(job);
        markVideoFailed(videoId);
    }

    @Transactional
    public void recordTransientFailure(Long jobId, UUID videoId, String error) {
        TranscodeJob job = jobRepository.findById(jobId).orElseThrow(() -> new NotFoundException("Job not found"));
        boolean exhausted = requeueOrFail(job, error);
        if (exhausted) {
            markVideoFailed(videoId);
        }
    }

    @Transactional
    public void recordMigrationSuccess(Long jobId, UUID videoId, String newPrefix) {
        TranscodeJob job = jobRepository.findById(jobId).orElseThrow(() -> new NotFoundException("Job not found"));
        job.setStatus(JobStatus.DONE);
        jobRepository.save(job);

        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        String oldPrefix = video.getStoragePrefix();
        video.setStoragePrefix(newPrefix);
        video.setPlaylistKey(StoragePrefixMover.rewriteKey(video.getPlaylistKey(), oldPrefix, newPrefix));
        video.setThumbnailKey(StoragePrefixMover.rewriteKey(video.getThumbnailKey(), oldPrefix, newPrefix));
        video.setSpriteSheetKey(StoragePrefixMover.rewriteKey(video.getSpriteSheetKey(), oldPrefix, newPrefix));

        Visibility newVisibility = video.getVisibilityTarget();
        video.setVisibility(newVisibility);
        video.setVisibilityTarget(null);
        // CLAUDE.md 9.5: a PRIVATE -> PUBLIC migration is the publication event, but only the
        // first time - a video already published stays at its original publishedAt across
        // later visibility changes.
        if (newVisibility == Visibility.PUBLIC && video.getPublishedAt() == null) {
            video.setPublishedAt(clock.instant());
        }
        videoRepository.save(video);

        videoRenditionRepository.findByVideoId(video.getId()).forEach(rendition ->
                rendition.setPlaylistKey(StoragePrefixMover.rewriteKey(rendition.getPlaylistKey(), oldPrefix, newPrefix)));
    }

    @Transactional
    public void recordMigrationTransientFailure(Long jobId, String error) {
        TranscodeJob job = jobRepository.findById(jobId).orElseThrow(() -> new NotFoundException("Job not found"));
        // Unlike recordTransientFailure, a video whose migration is retried or even permanently
        // fails must NOT be marked FAILED - it stays READY, still served from the old prefix at
        // the old visibility. Only visibilityTarget (still set) and the job's own FAILED status
        // record that a migration was attempted and didn't finish.
        requeueOrFail(job, error);
    }

    /**
     * @return true if retries are exhausted and the job is now permanently FAILED.
     */
    private boolean requeueOrFail(TranscodeJob job, String error) {
        job.setLastError(error);
        if (job.getAttempts() >= job.getMaxAttempts()) {
            job.setStatus(JobStatus.FAILED);
            jobRepository.save(job);
            return true;
        }
        job.setStatus(JobStatus.PENDING);
        job.setLockedAt(null);
        job.setLockedBy(null);
        job.setScheduledAt(clock.instant().plus(backoffFor(job.getAttempts())));
        jobRepository.save(job);
        return false;
    }

    private Duration backoffFor(int attempts) {
        List<Duration> backoff = properties.retryBackoff();
        int index = Math.min(Math.max(attempts - 1, 0), backoff.size() - 1);
        return backoff.get(index);
    }

    private void markVideoFailed(UUID videoId) {
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        video.setStatus(VideoStatus.FAILED);
        videoRepository.save(video);
    }
}
