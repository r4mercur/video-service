package com.bjarne.videoservice.catalog.service;

import com.bjarne.videoservice.catalog.dto.UpdateVideoRequest;
import com.bjarne.videoservice.catalog.dto.VideoDetailDto;
import com.bjarne.videoservice.catalog.entity.Category;
import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.entity.Visibility;
import com.bjarne.videoservice.catalog.repository.CategoryRepository;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.catalog.storage.StoragePrefixMover;
import com.bjarne.videoservice.delivery.service.MediaUrlResolver;
import com.bjarne.videoservice.moderation.entity.ReportStatus;
import com.bjarne.videoservice.moderation.repository.ReportRepository;
import com.bjarne.videoservice.shared.exceptions.ConflictException;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.bjarne.videoservice.transcoding.entity.JobStatus;
import com.bjarne.videoservice.transcoding.entity.JobType;
import com.bjarne.videoservice.transcoding.entity.TranscodeJob;
import com.bjarne.videoservice.transcoding.repository.TranscodeJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Write-side video management by the owner (AP7): changing metadata/visibility and
 * deleting incl. S3 cleanup. Ownership is checked exclusively via @PreAuthorize on the
 * controller (VideoOwnership bean, CLAUDE.md 3.2), not duplicated here.
 */
@Service
public class VideoManagementService {

    private final VideoRepository videoRepository;
    private final CategoryRepository categoryRepository;
    private final ReportRepository reportRepository;
    private final StoragePrefixMover storagePrefixMover;
    private final TranscodeJobRepository transcodeJobRepository;
    private final MediaUrlResolver urlResolver;
    private final ThumbnailService thumbnailService;
    private final Clock clock;

    public VideoManagementService(VideoRepository videoRepository, CategoryRepository categoryRepository,
                                   ReportRepository reportRepository,
                                   StoragePrefixMover storagePrefixMover, TranscodeJobRepository transcodeJobRepository,
                                   MediaUrlResolver urlResolver, ThumbnailService thumbnailService, Clock clock) {
        this.videoRepository = videoRepository;
        this.categoryRepository = categoryRepository;
        this.reportRepository = reportRepository;
        this.storagePrefixMover = storagePrefixMover;
        this.transcodeJobRepository = transcodeJobRepository;
        this.urlResolver = urlResolver;
        this.thumbnailService = thumbnailService;
        this.clock = clock;
    }

    @Transactional
    public UpdateVideoResult update(UUID videoId, UpdateVideoRequest request) {
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));

        if (request.title() != null) {
            video.setTitle(request.title());
        }
        if (request.description() != null) {
            video.setDescription(request.description());
        }
        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                    .filter(Category::isActive)
                    .orElseThrow(() -> new NotFoundException("Category not found"));
            video.setCategory(category);
        }

        boolean visibilityMigrationEnqueued = false;
        if (request.visibility() != null && request.visibility() != video.getVisibility()) {
            if (request.visibility() == Visibility.PUBLIC
                    && reportRepository.existsByVideoIdAndStatus(videoId, ReportStatus.OPEN)) {
                throw new ConflictException("Video cannot be made public while a report is open");
            }
            visibilityMigrationEnqueued = changeVisibility(video, request.visibility());
        }

        videoRepository.save(video);
        return new UpdateVideoResult(VideoDetailDto.from(video, urlResolver), visibilityMigrationEnqueued);
    }

    @Transactional
    public VideoDetailDto setThumbnail(UUID videoId, MultipartFile file) {
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        thumbnailService.store(video, file);
        return VideoDetailDto.from(video, urlResolver);
    }

    @Transactional
    public VideoDetailDto removeThumbnail(UUID videoId) {
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        thumbnailService.remove(video);
        return VideoDetailDto.from(video, urlResolver);
    }

    @Transactional
    public void delete(UUID videoId) {
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        if (reportRepository.existsByVideoIdAndStatus(videoId, ReportStatus.OPEN)) {
            throw new ConflictException("Video cannot be deleted while a report is open");
        }
        if (video.getStoragePrefix() != null) {
            storagePrefixMover.deleteAll(video.getStoragePrefix());
        }
        // Source lives under its own "source/{id}" prefix (UploadService), never under
        // storagePrefix, so the sweep above doesn't touch it. Safe to call unconditionally on
        // sourceDeletedAt: if SourceRetentionCleanupJob already removed it, the prefix is
        // already empty and this is a no-op.
        if (video.getSourceKey() != null) {
            storagePrefixMover.deleteAll("source/" + video.getId());
        }
        videoRepository.delete(video);
    }

    /**
     * CLAUDE.md 3.2/9.5: bulk-moving storage objects must never run in the request thread. This
     * only writes visibility_target and enqueues a VISIBILITY_MIGRATION job; the worker moves the
     * S3 objects and flips visibility itself once the move is fully done (TranscodeJobLifecycle).
     *
     * @return true if a migration job was enqueued (caller should respond 202 Accepted)
     */
    private boolean changeVisibility(Video video, Visibility newVisibility) {
        if (video.getStoragePrefix() == null) {
            // Nothing has been uploaded/processed yet - there's nothing to move.
            video.setVisibility(newVisibility);
            return false;
        }

        boolean migrationInFlight = transcodeJobRepository.existsByVideoIdAndTypeAndStatusIn(
                video.getId(), JobType.VISIBILITY_MIGRATION, List.of(JobStatus.PENDING, JobStatus.RUNNING));
        if (migrationInFlight) {
            throw new ConflictException("A visibility change is already in progress for this video: " + video.getId());
        }

        video.setVisibilityTarget(newVisibility);
        transcodeJobRepository.save(new TranscodeJob(video, clock.instant(), JobType.VISIBILITY_MIGRATION));
        return true;
    }
}
