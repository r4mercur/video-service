package com.bjarne.videoservice.moderation.service;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.catalog.service.VisibilityPolicy;
import com.bjarne.videoservice.identity.entity.User;
import com.bjarne.videoservice.identity.repository.UserRepository;
import com.bjarne.videoservice.moderation.dto.ReportResponse;
import com.bjarne.videoservice.moderation.dto.SubmitReportRequest;
import com.bjarne.videoservice.moderation.entity.Report;
import com.bjarne.videoservice.moderation.repository.ReportRepository;
import com.bjarne.videoservice.shared.RateLimiter;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.bjarne.videoservice.shared.exceptions.TooManyRequestsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ReportService {

    private final VideoRepository videoRepository;
    private final UserRepository userRepository;
    private final ReportRepository reportRepository;
    private final RateLimiter rateLimiter;

    public ReportService(VideoRepository videoRepository,
                         UserRepository userRepository,
                         ReportRepository reportRepository,
                         RateLimiter rateLimiter) {
        this.videoRepository = videoRepository;
        this.userRepository = userRepository;
        this.reportRepository = reportRepository;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public ReportResponse submit(UUID videoId, UUID reporterUserId, String clientIp, SubmitReportRequest request) {
        boolean withinLimit = reporterUserId != null
                ? rateLimiter.tryConsumeReport(reporterUserId.toString())
                : rateLimiter.tryConsumeReportAnonymous(clientIp);
        if (!withinLimit) {
            throw new TooManyRequestsException("Too many reports - please try again later");
        }
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        if (!VisibilityPolicy.isVisibleTo(video, reporterUserId)) {
            throw new NotFoundException("Video not found");
        }
        User reporter = reporterUserId != null ? userRepository.getReferenceById(reporterUserId) : null;
        Report report = new Report(video, reporter, request.reason().name(), request.detail());
        reportRepository.save(report);
        return ReportResponse.from(report);
    }
}
