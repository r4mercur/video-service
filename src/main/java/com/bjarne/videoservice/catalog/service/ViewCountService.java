package com.bjarne.videoservice.catalog.service;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.catalog.repository.VideoViewStatsRepository;
import com.bjarne.videoservice.config.ViewCountProperties;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * View counting, deduplicated (CLAUDE.md 7). IP-based instead of cookie-based (AP7 decision):
 * no extra state for a platform that's explicitly watchable without login. Weakness: multiple
 * viewers behind the same NAT IP only count once per window - tolerable overall at ~20
 * concurrent viewers (CLAUDE.md 1).
 */
@Service
public class ViewCountService {

    private final VideoRepository videoRepository;
    private final VideoViewStatsRepository statsRepository;
    private final Clock clock;
    private final Cache<String, Boolean> dedupCache;
    private final Counter countedViews;
    private final Counter deduplicatedViews;

    public ViewCountService(VideoRepository videoRepository, VideoViewStatsRepository statsRepository,
                             ViewCountProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.videoRepository = videoRepository;
        this.statsRepository = statsRepository;
        this.clock = clock;
        this.dedupCache = Caffeine.newBuilder()
                .expireAfterWrite(properties.dedupWindow())
                .maximumSize(100_000)
                .build();
        this.countedViews = Counter.builder("videoservice.views")
                .tag("result", "counted")
                .description("View events by dedup result")
                .register(meterRegistry);
        this.deduplicatedViews = Counter.builder("videoservice.views")
                .tag("result", "deduplicated")
                .description("View events by dedup result")
                .register(meterRegistry);
    }

    @Transactional
    public void recordView(UUID videoId, UUID viewerUserId, String clientIp) {
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new NotFoundException("Video not found"));
        if (!VisibilityPolicy.isVisibleTo(video, viewerUserId)) {
            throw new NotFoundException("Video not found");
        }
        String key = videoId + ":" + clientIp;
        boolean firstViewInWindow = dedupCache.asMap().putIfAbsent(key, Boolean.TRUE) == null;
        if (firstViewInWindow) {
            statsRepository.incrementViews(videoId, LocalDate.now(clock));
            countedViews.increment();
        } else {
            deduplicatedViews.increment();
        }
    }
}
