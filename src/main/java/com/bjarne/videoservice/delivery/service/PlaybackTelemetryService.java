package com.bjarne.videoservice.delivery.service;

import com.bjarne.videoservice.delivery.dto.PlaybackTelemetryRequest;
import com.bjarne.videoservice.shared.RateLimiter;
import com.bjarne.videoservice.shared.exceptions.TooManyRequestsException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Turns what a viewer's player actually experienced into Micrometer metrics.
 *
 * <p>This is the other half of {@link DeliveryOriginProbe}. The probe measures the origin from
 * inside the data centre; this measures the same origin from where it matters, the viewer's
 * browser. Neither replaces the other - when the probe looks healthy and this does not, the
 * problem is the path between them rather than the origin itself.
 *
 * <p><b>Cardinality is the design constraint here.</b> The input is anonymous and attacker-
 * controlled, and every tag value becomes a permanent Prometheus time series. So: no video id
 * (500+ videos, and it would also make playback behaviour per-video personal data adjacent), no
 * user id, no free-form strings at all. The only tag is the rendition height, folded onto the
 * known ladder so an invented value cannot create a new series.
 */
@Service
public class PlaybackTelemetryService {

    /** CLAUDE.md 9.2's ladder. Anything else is reported as "other" rather than as its own tag. */
    private static final Set<Integer> KNOWN_LADDER_HEIGHTS = Set.of(360, 720, 1080);

    private final MeterRegistry meterRegistry;
    private final RateLimiter rateLimiter;

    public PlaybackTelemetryService(MeterRegistry meterRegistry, RateLimiter rateLimiter) {
        this.meterRegistry = meterRegistry;
        this.rateLimiter = rateLimiter;
    }

    public void record(PlaybackTelemetryRequest request, String clientIp) {
        if (!rateLimiter.tryConsumePlaybackTelemetry(clientIp)) {
            throw new TooManyRequestsException("Too many playback telemetry reports - please try again later");
        }

        String height = heightTag(request.height());
        List<PlaybackTelemetryRequest.FragmentSample> fragments =
                request.fragments() == null ? List.of() : request.fragments();

        for (PlaybackTelemetryRequest.FragmentSample fragment : fragments) {
            Timer.builder("videoservice.playback.fragment.load")
                    .tag("height", height)
                    .description("How long a viewer's player waited for one media segment. With 4 s "
                            + "segments (HlsPackager), anything approaching 4 s means the buffer is "
                            + "draining faster than it fills and playback will stall.")
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(Duration.ofMillis(fragment.loadMs()));

            // Throughput is what makes a slow load interpretable: a slow segment on a fat pipe is
            // origin latency, a slow segment on a thin one is bandwidth. Guard against a zero
            // duration, which a cache hit legitimately produces and which would divide by zero.
            if (fragment.loadMs() > 0) {
                // No baseUnit(): the Prometheus registry appends it to the metric name, so
                // declaring one here would rename the series out from under the dashboard.
                // The unit (kbit/s - bytes*8/ms happens to give exactly that) lives in the
                // description and in the panel's own unit setting instead.
                DistributionSummary.builder("videoservice.playback.fragment.throughput")
                        .tag("height", height)
                        .description("Effective per-segment throughput in kbit/s as observed by the player")
                        .register(meterRegistry)
                        .record(fragment.bytes() * 8.0 / fragment.loadMs());
            }
        }

        increment("videoservice.playback.stalls", height,
                "Rebuffering events - the player ran out of buffered media and playback halted",
                request.stalls());
        increment("videoservice.playback.fragment.errors", height,
                "Segment requests the player gave up on (timeout, 5xx, abort)",
                request.fragmentErrors());

        if (request.startupMs() != null) {
            Timer.builder("videoservice.playback.startup")
                    .tag("height", height)
                    .description("Time from player initialisation to the first rendered frame")
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(Duration.ofMillis(request.startupMs()));
        }
    }

    private void increment(String name, String height, String description, int amount) {
        if (amount <= 0) {
            return;
        }
        Counter.builder(name)
                .tag("height", height)
                .description(description)
                .register(meterRegistry)
                .increment(amount);
    }

    private String heightTag(Integer height) {
        return height != null && KNOWN_LADDER_HEIGHTS.contains(height) ? String.valueOf(height) : "other";
    }
}
