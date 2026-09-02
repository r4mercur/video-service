package com.bjarne.videoservice.moderation.service;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.entity.VideoStatus;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.identity.entity.User;
import com.bjarne.videoservice.identity.repository.UserRepository;
import com.bjarne.videoservice.moderation.dto.AdminReportDto;
import com.bjarne.videoservice.moderation.entity.AuditLog;
import com.bjarne.videoservice.moderation.entity.AuditLogAction;
import com.bjarne.videoservice.moderation.entity.Report;
import com.bjarne.videoservice.moderation.entity.ReportStatus;
import com.bjarne.videoservice.moderation.repository.AuditLogRepository;
import com.bjarne.videoservice.moderation.repository.ReportRepository;
import com.bjarne.videoservice.shared.CursorCodec;
import com.bjarne.videoservice.shared.CursorPage;
import com.bjarne.videoservice.shared.exceptions.ConflictException;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.bjarne.videoservice.shared.exceptions.ValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Moderation actions (AP7): blocking/unblocking a video runs exclusively through
 * {@code video.status} (BLOCKED/READY), never through {@code visibility} - otherwise unblocking
 * would overwrite the original visibility chosen by the uploader. Every action creates an
 * immutable {@link AuditLog} entry with a reason (CLAUDE.md 12).
 */
@Service
public class AdminService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final Instant NO_CURSOR_TS = Instant.parse("9999-12-31T23:59:59Z");
    private static final Long NO_CURSOR_ID = Long.MAX_VALUE;

    private final VideoRepository videoRepository;
    private final ReportRepository reportRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public AdminService(VideoRepository videoRepository, ReportRepository reportRepository,
                         AuditLogRepository auditLogRepository, UserRepository userRepository, Clock clock) {
        this.videoRepository = videoRepository;
        this.reportRepository = reportRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public void blockVideo(UUID videoId, UUID adminUserId, String reason) {
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        if (video.getStatus() == VideoStatus.BLOCKED) {
            throw new ConflictException("Video is already blocked");
        }
        video.setStatus(VideoStatus.BLOCKED);
        videoRepository.save(video);
        writeAuditLog(adminUserId, AuditLogAction.VIDEO_BLOCKED, video, null, reason);
    }

    @Transactional
    public void unblockVideo(UUID videoId, UUID adminUserId, String reason) {
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        if (video.getStatus() != VideoStatus.BLOCKED) {
            throw new ConflictException("Video is not blocked");
        }
        video.setStatus(VideoStatus.READY);
        videoRepository.save(video);
        writeAuditLog(adminUserId, AuditLogAction.VIDEO_UNBLOCKED, video, null, reason);
    }

    @Transactional
    public AdminReportDto dismissReport(Long reportId, UUID adminUserId, String reason) {
        Report report = requireOpenReport(reportId);
        resolveReport(report, ReportStatus.DISMISSED, adminUserId);
        writeAuditLog(adminUserId, AuditLogAction.REPORT_DISMISSED, report.getVideo(), report, reason);
        return AdminReportDto.from(report);
    }

    @Transactional
    public AdminReportDto upholdReport(Long reportId, UUID adminUserId, String reason) {
        Report report = requireOpenReport(reportId);
        resolveReport(report, ReportStatus.REVIEWED, adminUserId);
        block(report.getVideo(), adminUserId, report, reason);
        writeAuditLog(adminUserId, AuditLogAction.REPORT_UPHELD, report.getVideo(), report, reason);
        return AdminReportDto.from(report);
    }

    @Transactional(readOnly = true)
    public CursorPage<AdminReportDto> listReports(ReportStatus status, String cursor, Integer limit) {
        int pageSize = resolveLimit(limit);
        CursorCodec.LongIdCursor decoded = cursor != null ? CursorCodec.decodeLong(cursor)
                : new CursorCodec.LongIdCursor(NO_CURSOR_TS, NO_CURSOR_ID);
        List<Report> reports = reportRepository.findPage(status, decoded.timestamp(), decoded.id(),
                PageRequest.of(0, pageSize + 1));

        boolean hasNext = reports.size() > pageSize;
        List<Report> pageItems = hasNext ? reports.subList(0, pageSize) : reports;
        String nextCursor = null;
        if (hasNext) {
            Report last = pageItems.get(pageItems.size() - 1);
            nextCursor = CursorCodec.encodeLong(last.getCreatedAt(), last.getId());
        }
        return new CursorPage<>(pageItems.stream().map(AdminReportDto::from).toList(), nextCursor);
    }

    /**
     * Only blocks if the video isn't already blocked - when upholding a report for a video
     * that's already blocked (e.g. from an earlier direct block), no duplicate VIDEO_BLOCKED
     * audit entry should be created; the report is still resolved normally.
     */
    private void block(Video video, UUID adminUserId, Report report, String reason) {
        if (video.getStatus() == VideoStatus.BLOCKED) {
            return;
        }
        video.setStatus(VideoStatus.BLOCKED);
        videoRepository.save(video);
        writeAuditLog(adminUserId, AuditLogAction.VIDEO_BLOCKED, video, report, reason);
    }

    private Report requireOpenReport(Long reportId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new NotFoundException("Report not found"));
        if (report.getStatus() != ReportStatus.OPEN) {
            throw new ConflictException("Report has already been handled");
        }
        return report;
    }

    private void resolveReport(Report report, ReportStatus newStatus, UUID adminUserId) {
        report.setStatus(newStatus);
        report.setHandledBy(userRepository.getReferenceById(adminUserId));
        report.setHandledAt(clock.instant());
        reportRepository.save(report);
    }

    private void writeAuditLog(UUID adminUserId, AuditLogAction action, Video video, Report report, String reason) {
        User actor = userRepository.getReferenceById(adminUserId);
        auditLogRepository.save(new AuditLog(actor, action, video, report, reason));
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ValidationException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }
}
