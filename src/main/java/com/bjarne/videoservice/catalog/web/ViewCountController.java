package com.bjarne.videoservice.catalog.web;

import com.bjarne.videoservice.catalog.service.ViewCountService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ViewCountController {

    private final ViewCountService viewCountService;

    public ViewCountController(ViewCountService viewCountService) {
        this.viewCountService = viewCountService;
    }

    @PostMapping("/api/videos/{id}/view")
    public ResponseEntity<Void> view(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                      HttpServletRequest request) {
        viewCountService.recordView(id, viewerUserId(jwt), request.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    private UUID viewerUserId(Jwt jwt) {
        return jwt != null ? UUID.fromString(jwt.getSubject()) : null;
    }
}
