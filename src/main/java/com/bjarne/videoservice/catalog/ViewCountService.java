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
 * View-Zaehlung, dedupliziert (CLAUDE.md 7). IP-basiert statt Cookie-basiert (AP7-Entscheidung):
 * kein zusaetzlicher State fuer eine Plattform, die explizit ohne Login ansehbar ist. Schwaeche:
 * mehrere Zuschauer hinter derselben NAT-IP zaehlen nur einmal pro Fenster - bei ~20 gleichzeitigen
 * Zuschauern insgesamt tolerierbar (CLAUDE.md 1).
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
