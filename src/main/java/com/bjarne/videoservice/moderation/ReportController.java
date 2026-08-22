package com.bjarne.videoservice.moderation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/api/videos/{id}/report")
    public ResponseEntity<ReportResponse> report(@PathVariable UUID id, @Valid @RequestBody SubmitReportRequest request,
                                                   JwtAuthenticationToken authentication) {
        UUID reporterUserId = UUID.fromString(authentication.getToken().getSubject());
        ReportResponse response = reportService.submit(id, reporterUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
