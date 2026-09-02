package com.bjarne.videoservice.moderation.service;

import com.bjarne.videoservice.moderation.entity.ReportStatus;
import com.bjarne.videoservice.moderation.repository.ReportRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Open reports as a DB-backed gauge, evaluated at scrape time. DSA notice-and-action (CLAUDE.md
 * 12) makes an unhandled report a compliance risk, not just a UX one - the moderation backlog
 * belongs on a dashboard, not only in the admin UI.
 */
@Component
public class ModerationMetrics {

    public ModerationMetrics(ReportRepository reportRepository, MeterRegistry registry) {
        Gauge.builder("videoservice.reports.open", () -> reportRepository.countByStatus(ReportStatus.OPEN))
                .description("Reports awaiting moderation (status OPEN)")
                .register(registry);
    }
}
