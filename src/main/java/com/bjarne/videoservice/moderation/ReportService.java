package com.bjarne.videoservice.moderation;

import com.bjarne.videoservice.catalog.Video;
import com.bjarne.videoservice.catalog.VideoRepository;
import com.bjarne.videoservice.catalog.VisibilityPolicy;
import com.bjarne.videoservice.identity.User;
import com.bjarne.videoservice.identity.UserRepository;
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

    public ReportService(VideoRepository videoRepository, UserRepository userRepository,
                          ReportRepository reportRepository, RateLimiter rateLimiter) {
        this.videoRepository = videoRepository;
        this.userRepository = userRepository;
        this.reportRepository = reportRepository;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public ReportResponse submit(UUID videoId, UUID reporterUserId, SubmitReportRequest request) {
        if (!rateLimiter.tryConsumeReport(reporterUserId.toString())) {
            throw new TooManyRequestsException("Zu viele Meldungen - bitte spaeter erneut versuchen");
        }
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        if (!VisibilityPolicy.isVisibleTo(video, reporterUserId)) {
            throw new NotFoundException("Video not found");
        }
        User reporter = userRepository.getReferenceById(reporterUserId);
        Report report = new Report(video, reporter, request.reason().name(), request.detail());
        reportRepository.save(report);
        return ReportResponse.from(report);
    }
}
