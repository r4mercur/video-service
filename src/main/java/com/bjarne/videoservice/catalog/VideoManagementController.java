package com.bjarne.videoservice.catalog;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
public class VideoManagementController {

    private final VideoManagementService videoManagementService;

    public VideoManagementController(VideoManagementService videoManagementService) {
        this.videoManagementService = videoManagementService;
    }

    @PatchMapping("/api/videos/{id}")
    @PreAuthorize("@videoOwnership.isOwner(#id, authentication)")
    public ResponseEntity<VideoDetailDto> update(@PathVariable UUID id, @Valid @RequestBody UpdateVideoRequest request) {
        UpdateVideoResult result = videoManagementService.update(id, request);
        return result.visibilityMigrationEnqueued()
                ? ResponseEntity.accepted().body(result.video())
                : ResponseEntity.ok(result.video());
    }

    @DeleteMapping("/api/videos/{id}")
    @PreAuthorize("@videoOwnership.isOwner(#id, authentication)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        videoManagementService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/videos/{id}/thumbnail")
    @PreAuthorize("@videoOwnership.isOwner(#id, authentication)")
    public VideoDetailDto setThumbnail(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return videoManagementService.setThumbnail(id, file);
    }

    @DeleteMapping("/api/videos/{id}/thumbnail")
    @PreAuthorize("@videoOwnership.isOwner(#id, authentication)")
    public VideoDetailDto removeThumbnail(@PathVariable UUID id) {
        return videoManagementService.removeThumbnail(id);
    }
}
