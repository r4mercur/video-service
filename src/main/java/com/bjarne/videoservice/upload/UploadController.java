package com.bjarne.videoservice.upload;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/api/videos")
    public ResponseEntity<InitiateUploadResponse> initiate(@Valid @RequestBody InitiateUploadRequest request,
                                                             JwtAuthenticationToken authentication) {
        UUID userId = UUID.fromString(authentication.getToken().getSubject());
        InitiateUploadResponse response = uploadService.initiate(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/videos/{id}/complete")
    @PreAuthorize("@videoOwnership.isOwner(#id, authentication)")
    public ResponseEntity<Void> complete(@PathVariable UUID id, @Valid @RequestBody CompleteUploadRequest request) {
        uploadService.complete(id, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/videos/{id}/status")
    @PreAuthorize("@videoOwnership.isOwner(#id, authentication)")
    public VideoStatusResponse status(@PathVariable UUID id) {
        return uploadService.status(id);
    }
}
