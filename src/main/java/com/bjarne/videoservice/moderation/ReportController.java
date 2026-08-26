package com.bjarne.videoservice.moderation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
                                                   @AuthenticationPrincipal Jwt jwt, HttpServletRequest servletRequest) {
        ReportResponse response = reportService.submit(id, reporterUserId(jwt), servletRequest.getRemoteAddr(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private UUID reporterUserId(Jwt jwt) {
        return jwt != null ? UUID.fromString(jwt.getSubject()) : null;
    }
}
