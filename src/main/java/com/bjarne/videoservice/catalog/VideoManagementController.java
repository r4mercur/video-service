package com.bjarne.videoservice.catalog;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class VideoManagementController {

    private final VideoManagementService videoManagementService;

    public VideoManagementController(VideoManagementService videoManagementService) {
        this.videoManagementService = videoManagementService;
    }

    @PatchMapping("/api/videos/{id}")
    @PreAuthorize("@videoOwnership.isOwner(#id, authentication)")
    public VideoDetailDto update(@PathVariable UUID id, @Valid @RequestBody UpdateVideoRequest request) {
        return videoManagementService.update(id, request);
    }

    @DeleteMapping("/api/videos/{id}")
    @PreAuthorize("@videoOwnership.isOwner(#id, authentication)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        videoManagementService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
