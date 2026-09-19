package com.bjarne.videoservice.delivery.service;

import com.bjarne.videoservice.config.RateLimitProperties;
import com.bjarne.videoservice.delivery.dto.PlaybackTelemetryRequest;
import com.bjarne.videoservice.shared.RateLimiter;
import com.bjarne.videoservice.shared.exceptions.TooManyRequestsException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PlaybackTelemetryServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void recordsFragmentLoadTimesAndThroughputUnderTheRenditionHeight() {
        PlaybackTelemetryService service = service(100);

        // 200 kB in 6 s - the shape production was actually serving: a 4 s segment arriving in
        // six, i.e. the buffer draining faster than it fills.
        service.record(request(360, List.of(sample(6000, 200_000)), 0, 0, null), "1.2.3.4");

        assertThat(registry.get("videoservice.playback.fragment.load").tag("height", "360").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("videoservice.playback.fragment.load").tag("height", "360").timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(6000);
        assertThat(registry.get("videoservice.playback.fragment.throughput").tag("height", "360").summary().mean())
                .isCloseTo(200_000 * 8.0 / 6000, org.assertj.core.data.Offset.offset(0.001));
    }

    /**
     * The endpoint is anonymous, so the height arrives attacker-controlled. Anything off the
     * ladder has to collapse into one bucket - otherwise a caller could mint an unbounded number
     * of Prometheus time series just by varying this number.
     */
    @Test
    void heightOffTheLadderCollapsesIntoASingleOtherSeries() {
        PlaybackTelemetryService service = service(100);

        service.record(request(12345, List.of(sample(100, 1000)), 0, 0, null), "1.2.3.4");
        service.record(request(99999, List.of(sample(100, 1000)), 0, 0, null), "1.2.3.4");
        service.record(request(null, List.of(sample(100, 1000)), 0, 0, null), "1.2.3.4");

        assertThat(registry.get("videoservice.playback.fragment.load").tag("height", "other").timer().count())
                .isEqualTo(3);
        assertThat(registry.find("videoservice.playback.fragment.load").tag("height", "12345").timer()).isNull();
        assertThat(registry.find("videoservice.playback.fragment.load").tag("height", "99999").timer()).isNull();
    }

    @Test
    void countsStallsAndFragmentErrors() {
        PlaybackTelemetryService service = service(100);

        service.record(request(720, List.of(), 3, 2, null), "1.2.3.4");

        assertThat(registry.get("videoservice.playback.stalls").tag("height", "720").counter().count())
                .isEqualTo(3.0);
        assertThat(registry.get("videoservice.playback.fragment.errors").tag("height", "720").counter().count())
                .isEqualTo(2.0);
    }

    /**
     * A cache hit legitimately reports a zero-millisecond load. Throughput is undefined there, so
     * it must be skipped rather than dividing by zero and poisoning the summary with Infinity.
     */
    @Test
    void zeroDurationFragmentIsTimedButProducesNoThroughputSample() {
        PlaybackTelemetryService service = service(100);

        service.record(request(720, List.of(sample(0, 200_000)), 0, 0, null), "1.2.3.4");

        assertThat(registry.get("videoservice.playback.fragment.load").tag("height", "720").timer().count())
                .isEqualTo(1);
        assertThat(registry.find("videoservice.playback.fragment.throughput").tag("height", "720").summary())
                .isNull();
    }

    @Test
    void startupTimeIsOptional() {
        PlaybackTelemetryService service = service(100);

        service.record(request(1080, List.of(), 0, 0, 2400L), "1.2.3.4");

        assertThat(registry.get("videoservice.playback.startup").tag("height", "1080").timer().count())
                .isEqualTo(1);
    }

    @Test
    void rejectsOnceTheIpExceedsItsRateLimit() {
        PlaybackTelemetryService service = service(1);

        service.record(request(360, List.of(), 0, 0, null), "9.9.9.9");

        assertThatExceptionOfType(TooManyRequestsException.class)
                .isThrownBy(() -> service.record(request(360, List.of(), 0, 0, null), "9.9.9.9"));
    }

    private PlaybackTelemetryService service(int telemetryCapacity) {
        RateLimiter rateLimiter = new RateLimiter(new RateLimitProperties(
                new RateLimitProperties.Limit(5, Duration.ofMinutes(15)),
                new RateLimitProperties.Limit(3, Duration.ofHours(1)),
                new RateLimitProperties.Limit(10, Duration.ofHours(1)),
                new RateLimitProperties.Limit(3, Duration.ofHours(1)),
                new RateLimitProperties.Limit(telemetryCapacity, Duration.ofMinutes(5)),
                new RateLimitProperties.Limit(10, Duration.ofHours(1))), registry);
        return new PlaybackTelemetryService(registry, rateLimiter);
    }

    private static PlaybackTelemetryRequest request(Integer height,
                                                    List<PlaybackTelemetryRequest.FragmentSample> fragments,
                                                    int stalls, int errors, Long startupMs) {
        return new PlaybackTelemetryRequest(height, fragments, stalls, errors, startupMs);
    }

    private static PlaybackTelemetryRequest.FragmentSample sample(long loadMs, long bytes) {
        return new PlaybackTelemetryRequest.FragmentSample(loadMs, bytes);
    }
}
