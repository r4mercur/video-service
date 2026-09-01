package com.bjarne.videoservice.catalog;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Video counts by status as DB-backed gauges, evaluated at scrape time (same reasoning as
 * transcoding.JobQueueMetrics: cheap COUNTs against the (status, visibility, published_at)
 * index from V1). Videos stuck in UPLOADING/PROCESSING or piling up in FAILED are the
 * dashboard's earliest hint that the pipeline is broken end-to-end.
 */
@Component
public class CatalogMetrics {

    public CatalogMetrics(VideoRepository videoRepository, MeterRegistry registry) {
        for (VideoStatus status : VideoStatus.values()) {
            Gauge.builder("videoservice.videos", () -> videoRepository.countByStatus(status))
                    .tag("status", status.name().toLowerCase())
                    .description("Videos by status")
                    .register(registry);
        }
    }
}
