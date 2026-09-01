package com.bjarne.videoservice.shared;

import com.bjarne.videoservice.config.RateLimitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final RateLimiter rateLimiter = new RateLimiter(new RateLimitProperties(
            new RateLimitProperties.Limit(2, Duration.ofMinutes(15)),
            new RateLimitProperties.Limit(1, Duration.ofHours(1)),
            new RateLimitProperties.Limit(3, Duration.ofHours(1)),
            new RateLimitProperties.Limit(1, Duration.ofHours(1))), meterRegistry);

    @Test
    void allowsUpToCapacityThenDeniesForSameKey() {
        assertThat(rateLimiter.tryConsumeLogin("1.2.3.4")).isTrue();
        assertThat(rateLimiter.tryConsumeLogin("1.2.3.4")).isTrue();
        assertThat(rateLimiter.tryConsumeLogin("1.2.3.4")).isFalse();
    }

    @Test
    void countsRejectionsPerLimiter() {
        rateLimiter.tryConsumeLogin("1.2.3.4");
        rateLimiter.tryConsumeLogin("1.2.3.4");
        rateLimiter.tryConsumeLogin("1.2.3.4");

        assertThat(meterRegistry.counter("videoservice.ratelimit.rejected", "limiter", "login").count())
                .isEqualTo(1.0);
        // Allowed requests and other limiters don't count.
        assertThat(meterRegistry.counter("videoservice.ratelimit.rejected", "limiter", "register").count())
                .isEqualTo(0.0);
    }

    @Test
    void tracksEachKeyIndependently() {
        assertThat(rateLimiter.tryConsumeRegister("1.2.3.4")).isTrue();
        assertThat(rateLimiter.tryConsumeRegister("1.2.3.4")).isFalse();

        assertThat(rateLimiter.tryConsumeRegister("5.6.7.8")).isTrue();
    }

    @Test
    void separatePurposesDoNotShareBuckets() {
        assertThat(rateLimiter.tryConsumeReport("user-1")).isTrue();
        assertThat(rateLimiter.tryConsumeReport("user-1")).isTrue();
        assertThat(rateLimiter.tryConsumeReport("user-1")).isTrue();
        assertThat(rateLimiter.tryConsumeReport("user-1")).isFalse();

        assertThat(rateLimiter.tryConsumeLogin("user-1")).isTrue();
    }

    @Test
    void anonymousReportsUseTheirOwnStricterBucket() {
        assertThat(rateLimiter.tryConsumeReportAnonymous("1.2.3.4")).isTrue();
        assertThat(rateLimiter.tryConsumeReportAnonymous("1.2.3.4")).isFalse();

        // Doesn't share capacity with the per-user report bucket.
        assertThat(rateLimiter.tryConsumeReport("1.2.3.4")).isTrue();
    }
}
