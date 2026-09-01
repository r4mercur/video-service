package com.bjarne.videoservice.upload;

import com.bjarne.videoservice.catalog.*;
import com.bjarne.videoservice.config.UploadProperties;
import com.bjarne.videoservice.identity.UserRepository;
import com.bjarne.videoservice.shared.exceptions.ConflictException;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.bjarne.videoservice.shared.exceptions.ValidationException;
import com.bjarne.videoservice.transcoding.TranscodeJob;
import com.bjarne.videoservice.transcoding.TranscodeJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class UploadService {

    private final CategoryRepository categoryRepository;
    private final VideoRepository videoRepository;
    private final UserRepository userRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final TranscodeJobRepository transcodeJobRepository;
    private final S3MultipartClient s3MultipartClient;
    private final UploadProperties properties;
    private final Clock clock;

    public UploadService(CategoryRepository categoryRepository, VideoRepository videoRepository,
                          UserRepository userRepository, UploadSessionRepository uploadSessionRepository,
                          TranscodeJobRepository transcodeJobRepository, S3MultipartClient s3MultipartClient,
                          UploadProperties properties, Clock clock) {
        this.categoryRepository = categoryRepository;
        this.videoRepository = videoRepository;
        this.userRepository = userRepository;
        this.uploadSessionRepository = uploadSessionRepository;
        this.transcodeJobRepository = transcodeJobRepository;
        this.s3MultipartClient = s3MultipartClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public InitiateUploadResponse initiate(UUID userId, InitiateUploadRequest request) {
        if (request.sizeBytes() > properties.maxSizeBytes()) {
            throw new ValidationException("File exceeds maximum size of " + properties.maxSizeBytes() + " bytes");
        }
        if (!request.contentType().toLowerCase(Locale.ROOT).startsWith("video/")) {
            throw new ValidationException("contentType must be a video/* type");
        }
        Category category = categoryRepository.findById(request.categoryId())
                .filter(Category::isActive)
                .orElseThrow(() -> new NotFoundException("Category not found"));

        Video video = new Video(userRepository.getReferenceById(userId), category, request.title(),
                buildUniqueSlug(request.title()), request.visibility());
        video.setDescription(request.description());
        videoRepository.save(video);

        String storagePrefix = (request.visibility() == Visibility.PUBLIC ? "public/" : "private/") + video.getId();
        video.setStoragePrefix(storagePrefix);
        videoRepository.save(video);

        // Deliberately NOT under storagePrefix (public/{id} or private/{id}): the source is
        // never served, never public, and needs its own lifecycle (30-day retention cleanup,
        // SourceRetentionCleanupJob) independent of the renditions living in that prefix.
        String s3Key = "source/" + video.getId() + "/source" + extractExtension(request.filename());
        String uploadId = s3MultipartClient.createMultipartUpload(s3Key, request.contentType());

        int partCount = (int) Math.ceilDiv(request.sizeBytes(), properties.partSizeBytes());
        List<UploadPartUrl> parts = new ArrayList<>(partCount);
        for (int partNumber = 1; partNumber <= partCount; partNumber++) {
            URL url = s3MultipartClient.presignPartUrl(s3Key, uploadId, partNumber, properties.partUrlTtl());
            parts.add(new UploadPartUrl(partNumber, url.toString()));
        }

        Instant expiresAt = clock.instant().plus(properties.sessionTtl());
        UploadSession session = new UploadSession(video, uploadId, s3Key, expiresAt);
        uploadSessionRepository.save(session);

        return new InitiateUploadResponse(video.getId(), parts, properties.partSizeBytes(), expiresAt);
    }

    @Transactional
    public void complete(UUID videoId, CompleteUploadRequest request) {
        UploadSession session = uploadSessionRepository
                .findFirstByVideoIdAndCompletedAtIsNullOrderByIdDesc(videoId)
                .orElseThrow(() -> new ConflictException("No active upload session for this video"));
        if (session.getExpiresAt().isBefore(clock.instant())) {
            throw new ConflictException("Upload session expired");
        }

        s3MultipartClient.completeMultipartUpload(session.getS3Key(), session.getS3UploadId(), request.parts());
        long actualSize = s3MultipartClient.headObjectSize(session.getS3Key());

        Video video = session.getVideo();
        video.setSizeBytes(actualSize);
        video.setSourceKey(session.getS3Key());
        video.setStatus(VideoStatus.PROCESSING);
        videoRepository.save(video);

        session.setCompletedAt(clock.instant());
        uploadSessionRepository.save(session);

        transcodeJobRepository.save(new TranscodeJob(video, clock.instant()));
    }

    @Transactional(readOnly = true)
    public VideoStatusResponse status(UUID videoId) {
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        Optional<TranscodeJob> latestJob = transcodeJobRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId);
        String lastError = latestJob.map(TranscodeJob::getLastError).orElse(null);
        int progressPercent = latestJob.map(TranscodeJob::getProgressPercent).orElse(0);
        String currentStep = latestJob.map(TranscodeJob::getCurrentStep).orElse(null);
        return new VideoStatusResponse(video.getStatus(), video.getVisibilityTarget(), lastError, progressPercent,
                currentStep);
    }

    private String buildUniqueSlug(String title) {
        String base = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "video";
        }
        if (base.length() > 200) {
            base = base.substring(0, 200);
        }
        String slug = base;
        while (videoRepository.existsBySlug(slug)) {
            slug = base + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return slug;
    }

    private String extractExtension(String filename) {
        String sanitized = filename.replaceAll("[^a-zA-Z0-9._-]", "");
        int dot = sanitized.lastIndexOf('.');
        if (dot < 0 || dot == sanitized.length() - 1) {
            return "";
        }
        String extension = sanitized.substring(dot);
        return extension.length() > 10 ? "" : extension;
    }
}
