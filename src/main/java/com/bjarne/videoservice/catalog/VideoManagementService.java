package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.delivery.MediaUrlResolver;
import com.bjarne.videoservice.moderation.ReportRepository;
import com.bjarne.videoservice.moderation.ReportStatus;
import com.bjarne.videoservice.shared.exceptions.ConflictException;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.bjarne.videoservice.transcoding.VideoRenditionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Schreibende Video-Verwaltung durch den Owner (AP7): Metadaten/Sichtbarkeit aendern und
 * Loeschen inkl. S3-Cleanup. Ownership wird ausschliesslich per @PreAuthorize am Controller
 * geprueft (VideoOwnership-Bean, CLAUDE.md 3.2), hier nicht dupliziert.
 */
@Service
public class VideoManagementService {

    private final VideoRepository videoRepository;
    private final CategoryRepository categoryRepository;
    private final VideoRenditionRepository videoRenditionRepository;
    private final ReportRepository reportRepository;
    private final StoragePrefixMover storagePrefixMover;
    private final MediaUrlResolver urlResolver;

    public VideoManagementService(VideoRepository videoRepository, CategoryRepository categoryRepository,
                                   VideoRenditionRepository videoRenditionRepository, ReportRepository reportRepository,
                                   StoragePrefixMover storagePrefixMover, MediaUrlResolver urlResolver) {
        this.videoRepository = videoRepository;
        this.categoryRepository = categoryRepository;
        this.videoRenditionRepository = videoRenditionRepository;
        this.reportRepository = reportRepository;
        this.storagePrefixMover = storagePrefixMover;
        this.urlResolver = urlResolver;
    }

    @Transactional
    public VideoDetailDto update(UUID videoId, UpdateVideoRequest request) {
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
        if (request.visibility() != null && request.visibility() != video.getVisibility()) {
            changeVisibility(video, request.visibility());
        }

        videoRepository.save(video);
        return VideoDetailDto.from(video, urlResolver);
    }

    @Transactional
    public void delete(UUID videoId) {
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        if (reportRepository.existsByVideoIdAndStatus(videoId, ReportStatus.OPEN)) {
            throw new ConflictException("Video kann nicht geloescht werden, solange eine Meldung offen ist");
        }
        if (video.getStoragePrefix() != null) {
            storagePrefixMover.deleteAll(video.getStoragePrefix());
        }
        videoRepository.delete(video);
    }

    private void changeVisibility(Video video, Visibility newVisibility) {
        String oldPrefix = video.getStoragePrefix();
        if (oldPrefix == null) {
            video.setVisibility(newVisibility);
            return;
        }
        String newPrefix = (newVisibility == Visibility.PUBLIC ? "public/" : "private/") + video.getId();
        storagePrefixMover.move(oldPrefix, newPrefix);

        video.setStoragePrefix(newPrefix);
        video.setSourceKey(StoragePrefixMover.rewriteKey(video.getSourceKey(), oldPrefix, newPrefix));
        video.setPlaylistKey(StoragePrefixMover.rewriteKey(video.getPlaylistKey(), oldPrefix, newPrefix));
        video.setThumbnailKey(StoragePrefixMover.rewriteKey(video.getThumbnailKey(), oldPrefix, newPrefix));
        video.setSpriteSheetKey(StoragePrefixMover.rewriteKey(video.getSpriteSheetKey(), oldPrefix, newPrefix));
        video.setVisibility(newVisibility);

        videoRenditionRepository.findByVideoId(video.getId()).forEach(rendition ->
                rendition.setPlaylistKey(StoragePrefixMover.rewriteKey(rendition.getPlaylistKey(), oldPrefix, newPrefix)));
    }
}
