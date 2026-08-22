package com.bjarne.videoservice.transcoding;

import com.bjarne.videoservice.catalog.Video;
import com.bjarne.videoservice.catalog.VideoRepository;
import com.bjarne.videoservice.catalog.VideoRendition;
import com.bjarne.videoservice.catalog.VideoStatus;
import com.bjarne.videoservice.config.TranscodeProperties;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
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
 * Alle DB-Zustandsuebergaenge fuer Transcode-Jobs, jeweils in einer eigenen kurzen Transaktion -
 * bewusst getrennt von {@link TranscodeService} (der die eigentliche, lange laufende Arbeit
 * ausserhalb jeder Transaktion erledigt) und von {@link JobPoller} (reiner Scheduler/Orchestrator,
 * damit @Transactional hier nicht durch Selbstaufruf umgangen wird).
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
                    "Worker-Timeout: Job war laenger als " + properties.staleJobTimeout() + " gesperrt");
            if (exhausted) {
                markVideoFailed(job.getVideo().getId());
            }
        }
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
        return Optional.of(new ClaimedJob(job.getId(), videoId));
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
        if (outcome.hasThumbnail()) {
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

    /**
     * @return true, wenn die Retries ausgeschoepft sind und der Job endgueltig FAILED ist.
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
