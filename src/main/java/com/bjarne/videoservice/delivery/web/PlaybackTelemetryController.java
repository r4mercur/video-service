package com.bjarne.videoservice.delivery.web;

import com.bjarne.videoservice.delivery.dto.PlaybackTelemetryRequest;
import com.bjarne.videoservice.delivery.service.PlaybackTelemetryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingest for real-user playback metrics. Anonymous, because watching requires no account
 * (CLAUDE.md 1) and telemetry that only covered logged-in viewers would systematically miss most
 * of the audience - see SecurityConfig for the permitAll matcher.
 *
 * <p>Returns 204: the caller is a fire-and-forget beacon on a page that is busy playing video,
 * so there is nothing useful to send back and no reason to make it parse a body.
 */
@RestController
public class PlaybackTelemetryController {

    private final PlaybackTelemetryService telemetryService;

    public PlaybackTelemetryController(PlaybackTelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @PostMapping("/api/playback/telemetry")
    public ResponseEntity<Void> report(@Valid @RequestBody PlaybackTelemetryRequest request,
                                       HttpServletRequest servletRequest) {
        telemetryService.record(request, servletRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
