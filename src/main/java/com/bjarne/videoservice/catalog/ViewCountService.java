package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.config.ViewCountProperties;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

    public ViewCountService(VideoRepository videoRepository, VideoViewStatsRepository statsRepository,
                             ViewCountProperties properties, Clock clock) {
        this.videoRepository = videoRepository;
        this.statsRepository = statsRepository;
        this.clock = clock;
        this.dedupCache = Caffeine.newBuilder()
                .expireAfterWrite(properties.dedupWindow())
                .maximumSize(100_000)
                .build();
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
        }
    }
}
